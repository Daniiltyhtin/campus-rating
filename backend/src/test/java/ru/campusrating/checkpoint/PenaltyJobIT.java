package ru.campusrating.checkpoint;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.campusrating.checkpoint.domain.PenaltyPhase;
import ru.campusrating.checkpoint.internal.PenaltyJob;
import ru.campusrating.checkpoint.internal.SubmissionService;
import ru.campusrating.identity.AuthenticatedUser;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.GroupRef;
import ru.campusrating.identity.Role;
import ru.campusrating.identity.TeacherScope;
import ru.campusrating.identity.UserRef;
import ru.campusrating.ledger.Currency;
import ru.campusrating.ledger.EntryKind;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.ledger.LedgerEntryView;
import ru.campusrating.ledger.Posting;
import ru.campusrating.ledger.Source;
import ru.campusrating.ledger.SourceType;
import ru.campusrating.settings.SettingsApi;
import ru.campusrating.support.MutableClock;
import ru.campusrating.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class PenaltyJobIT extends PostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired LedgerApi ledger;
    @Autowired PenaltyJob job;
    @Autowired SubmissionService submissions;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean CurrentUser current;
    @MockitoBean GroupMembers members;
    @MockitoBean TeacherScope scope;
    @Autowired SettingsApi settings;
    long teacher, student, faculty, group;
    Instant deadline;

    @BeforeEach
    void fixture() {
        drainEvents();
        deadline = clock.instant();
        teacher = person("TEACHER");
        student = person("STUDENT");
        faculty =
                jdbc.queryForObject(
                        "insert into faculty(code,name) values (?,'Факультет') returning id",
                        Long.class,
                        UUID.randomUUID().toString());
        group =
                jdbc.queryForObject(
                        "insert into study_group(code,name,faculty_id,admission_year) values (?,'Группа',?,2026) returning id",
                        Long.class,
                        UUID.randomUUID().toString(),
                        faculty);
        doReturn(Map.of(teacher, new UserRef(teacher, "Преподаватель")))
                .when(members)
                .users(anyCollection());
        doReturn(Map.of(group, new GroupRef(group, "Группа", faculty, "Ф", "Факультет")))
                .when(members)
                .groups(anyCollection());
        actor(student, Role.STUDENT);
        post(EntryKind.INITIAL, 1000, "initial:" + student, SourceType.REGISTRATION, student);
    }

    @AfterEach
    void cleanup() {
        drainEvents();
        jdbc.execute("drop trigger if exists penalty_test_failure on assignment");
        jdbc.execute("drop function if exists penalty_test_failure()");
        jdbc.update(
                "delete from submission where assignment_id in (select id from assignment where student_id=?)",
                student);
        jdbc.update("delete from assignment where student_id=?", student);
        jdbc.update("delete from checkpoint where author_id=?", teacher);
        jdbc.update("delete from ledger_entry where student_id=?", student);
        jdbc.update("delete from ledger_balance where student_id=?", student);
        jdbc.update("delete from app_user where id in (?,?)", student, teacher);
        jdbc.update("delete from study_group where id=?", group);
        jdbc.update("delete from faculty where id=?", faculty);
    }

    @Test
    void basicPenalty() {
        long id = assignment(60);
        pass(0);
        assertThat(penalties()).isEmpty();
        assertThat(slot(id)).isNull();
        pass(1);
        assertPenalty(id, 20, PenaltyPhase.RUNNING);
        assertThat(submissions.get(id).penalty().nextAt()).isEqualTo(deadline.plusSeconds(60));
        pass(61);
        pass(121);
        pass(181);
        assertThat(penalties()).extracting(LedgerEntryView::amount).containsExactly(-20, -20, -20);
        assertThat(ledger.balances(student).ar()).isEqualTo(940);
        assertPenalty(id, 60, PenaltyPhase.CAP_REACHED);
        assertThat(slot(id)).isEqualTo(2L);
        var entry = penalties().getFirst();
        assertThat(entry.source())
                .isEqualTo(new Source(SourceType.CHECKPOINT, checkpoint(id), "Работа"));
        assertThat(entry.author()).isNull();
        assertThat(ledger.reconcile().mismatches()).isEmpty();
    }

    @Test
    void submissionStopsPenalty() throws Exception {
        long id = assignment(60);
        pass(1);
        pass(61);
        clock.set(deadline.plusSeconds(90));
        submissions.submit(id, "https://example.org/work", null);
        pass(121);
        pass(181);
        assertThat(ledger.balances(student).ar()).isEqualTo(960);
        assertThat(penalties()).hasSize(2);
        assertPenalty(id, 40, PenaltyPhase.STOPPED);
        assertThat(submissions.get(id).state()).isEqualTo(AssignmentState.SUBMITTED);
    }

    @Test
    void returnResumesAtCurrentSlotWithSharedCap() throws Exception {
        long id = assignment(100);
        pass(1);
        pass(61);
        clock.set(deadline.plusSeconds(90));
        submissions.submit(id, "https://example.org/work", null);
        pass(241);
        clock.set(deadline.plusSeconds(310));
        actor(teacher, Role.TEACHER);
        submissions.returnBack(id, "Доработать");
        job.runOnce();
        assertThat(slot(id)).isEqualTo(5L);
        pass(361);
        pass(421);
        pass(481);
        assertThat(penalties()).hasSize(5);
        assertThat(ledger.balances(student).ar()).isEqualTo(900);
        assertPenalty(id, 100, PenaltyPhase.CAP_REACHED);
    }

    @Test
    void zeroBalanceMarksSlotAndTopUpDoesNotReplayIt() {
        long id = assignment(100);
        post(EntryKind.PENALTY, -990, "fixture:" + id, SourceType.CHECKPOINT, checkpoint(id));
        pass(1);
        assertPenalty(id, 10, PenaltyPhase.ZERO_BALANCE);
        pass(61);
        assertThat(slot(id)).isEqualTo(1L);
        assertThat(penalties()).hasSize(2);
        post(EntryKind.SEMESTER_ACCRUAL, 200, "topup:" + id, SourceType.SEMESTER, 1L);
        job.runOnce();
        assertThat(ledger.balances(student).ar()).isEqualTo(200);
        assertThat(slot(id)).isEqualTo(1L);
        assertPenalty(id, 10, PenaltyPhase.RUNNING);
        pass(121);
        assertPenalty(id, 30, PenaltyPhase.RUNNING);
        assertThat(ledger.balances(student).ar()).isEqualTo(180);
        assertThat(penalties()).extracting(LedgerEntryView::amount).containsExactly(-20, -10, -990);
    }

    @Test
    void parallelPassesChargeOnce() throws Exception {
        long id = assignment(100);
        clock.set(deadline.plusSeconds(181));
        race(job::runOnce, job::runOnce);
        assertThat(penalties()).hasSize(1);
        assertPenalty(id, 20, PenaltyPhase.RUNNING);
        assertThat(slot(id)).isEqualTo(3L);
        assertThat(ledger.balances(student).ar()).isEqualTo(980);
    }

    @Test
    void downtimeSkipsMissedSlots() {
        long id = assignment(100);
        pass(1);
        pass(330);
        job.runOnce();
        assertThat(penalties()).hasSize(2);
        assertThat(slot(id)).isEqualTo(5L);
        assertPenalty(id, 40, PenaltyPhase.RUNNING);
        assertThat(ledger.balances(student).ar()).isEqualTo(960);
    }

    @Test
    void submissionAndPenaltySerialize() throws Exception {
        long id = assignment(100);
        clock.set(deadline.plusSeconds(1));
        race(
                job::runOnce,
                () -> {
                    try {
                        submissions.submit(id, "https://example.org/work", null);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });
        int charged = submissions.get(id).penalty().charged();
        assertThat(charged).isIn(0, 20);
        assertThat(submissions.get(id).state()).isEqualTo(AssignmentState.SUBMITTED);
        assertThat(penalties()).hasSize(charged / 20);
        pass(121);
        assertPenalty(id, charged, PenaltyPhase.STOPPED);
        assertThat(ledger.balances(student).ar()).isEqualTo(1000 - charged);
    }

    @Test
    void alreadyPostedOnlyAdvancesSlot() {
        long id = assignment(100);
        post(EntryKind.PENALTY, -20, "penalty:" + id + ":0", SourceType.CHECKPOINT, checkpoint(id));
        jdbc.update("update assignment set charged=20 where id=?", id);
        pass(1);
        job.runOnce();
        assertPenalty(id, 20, PenaltyPhase.RUNNING);
        assertThat(slot(id)).isZero();
        assertThat(penalties()).hasSize(1);
        assertThat(ledger.balances(student).ar()).isEqualTo(980);
    }

    @Test
    void failedAssignmentRollsBackLedgerAndDoesNotStopBatch() {
        long failed = assignment(100);
        long good = assignment(100);
        jdbc.execute(
                """
                create function penalty_test_failure() returns trigger language plpgsql as $$
                begin
                    if NEW.id = %d then raise exception 'test failure'; end if;
                    return NEW;
                end $$
                """
                        .formatted(failed));
        jdbc.execute(
                "create trigger penalty_test_failure before update on assignment for each row execute function penalty_test_failure()");
        pass(1);
        assertPenalty(failed, 0, PenaltyPhase.RUNNING);
        assertThat(slot(failed)).isNull();
        assertPenalty(good, 20, PenaltyPhase.RUNNING);
        assertThat(ledger.balances(student).ar()).isEqualTo(980);
        assertThat(penalties()).hasSize(1);
        jdbc.execute("drop trigger penalty_test_failure on assignment");
        job.runOnce();
        assertPenalty(failed, 20, PenaltyPhase.RUNNING);
        assertThat(ledger.balances(student).ar()).isEqualTo(960);
        assertThat(ledger.reconcile().mismatches()).isEmpty();
    }

    @Test
    void candidatesAreLimitedToFiveHundredInIdOrder() {
        long first = assignment(1);
        long last = first;
        for (int i = 0; i < 500; i++) {
            last = assignment(1);
        }
        pass(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from assignment where student_id=? and charged=1",
                                Integer.class,
                                student))
                .isEqualTo(500);
        assertThat(slot(first)).isZero();
        assertThat(slot(last)).isNull();
        job.runOnce();
        assertPenalty(last, 1, PenaltyPhase.CAP_REACHED);
        assertThat(ledger.balances(student).ar()).isEqualTo(499);
    }

    @Test
    void checkpointParametersAndSqlSlotBoundariesAgree() {
        long id = assignment(45);
        jdbc.update(
                "update checkpoint set penalty_step=13,penalty_interval_seconds=7 where id=?",
                checkpoint(id));
        pass(1);
        assertPenalty(id, 13, PenaltyPhase.RUNNING);
        clock.set(deadline.plusMillis(6999));
        job.runOnce();
        assertPenalty(id, 13, PenaltyPhase.RUNNING);
        assertThat(slot(id)).isZero();
        pass(7);
        assertPenalty(id, 26, PenaltyPhase.RUNNING);
        assertThat(slot(id)).isEqualTo(1L);
        pass(14);
        pass(21);
        assertPenalty(id, 45, PenaltyPhase.CAP_REACHED);
        assertThat(penalties())
                .extracting(LedgerEntryView::amount)
                .containsExactly(-6, -13, -13, -13);
    }

    @Test
    void futureDeadlineAcceptedAndFutureProcessedSlotAreSkipped() {
        long future = assignment(60);
        jdbc.update(
                "update checkpoint set deadline=? where id=?",
                Timestamp.from(deadline.plusSeconds(600)),
                checkpoint(future));
        long accepted = assignment(60);
        jdbc.update(
                "update assignment set state='ACCEPTED',accepted_at=? where id=?",
                Timestamp.from(deadline),
                accepted);
        long processed = assignment(60);
        jdbc.update("update assignment set last_penalty_slot=2 where id=?", processed);
        pass(61);
        assertThat(penalties()).isEmpty();
        assertThat(slot(future)).isNull();
        assertThat(slot(accepted)).isNull();
        assertThat(slot(processed)).isEqualTo(2L);
        assertThat(ledger.balances(student).ar()).isEqualTo(1000);
    }

    @Test
    void triggerReadsChangedPollPeriodBeforeEachPass() {
        var previous =
                jdbc.queryForList(
                        "select value from settings where key='penaltyPollPeriod'", String.class);
        var registrar = new ScheduledTaskRegistrar();
        try {
            jdbc.update(
                    "insert into settings(key,value,type) values ('penaltyPollPeriod','PT1S','DURATION') on conflict (key) do update set value=excluded.value");
            job.configureTasks(registrar);
            var task = registrar.getTriggerTaskList().getFirst();
            var context = new SimpleTriggerContext(clock);
            assertThat(task.getTrigger().nextExecution(context))
                    .isEqualTo(clock.instant().plusSeconds(1));
            jdbc.update("update settings set value='PT7S' where key='penaltyPollPeriod'");
            context.update(deadline, deadline, deadline.plusSeconds(2));
            assertThat(task.getTrigger().nextExecution(context)).isEqualTo(deadline.plusSeconds(9));
            task.getRunnable().run();
        } finally {
            registrar.destroy();
            if (previous.isEmpty()) {
                jdbc.update("delete from settings where key='penaltyPollPeriod'");
            } else {
                jdbc.update(
                        "update settings set value=? where key='penaltyPollPeriod'",
                        previous.getFirst());
            }
        }
    }

    private void race(Runnable first, Runnable second) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a =
                    executor.submit(
                            () -> {
                                barrier.await(10, TimeUnit.SECONDS);
                                first.run();
                                return null;
                            });
            var b =
                    executor.submit(
                            () -> {
                                barrier.await(10, TimeUnit.SECONDS);
                                second.run();
                                return null;
                            });
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        }
    }

    private void pass(long seconds) {
        clock.set(deadline.plusSeconds(seconds));
        job.runOnce();
    }

    private void assertPenalty(long id, int charged, PenaltyPhase phase) {
        var penalty = submissions.get(id).penalty();
        assertThat(penalty.charged()).isEqualTo(charged);
        assertThat(penalty.phase()).isEqualTo(phase);
    }

    private Long slot(long id) {
        return jdbc.queryForObject(
                "select last_penalty_slot from assignment where id=?", Long.class, id);
    }

    private long checkpoint(long id) {
        return jdbc.queryForObject(
                "select checkpoint_id from assignment where id=?", Long.class, id);
    }

    private List<LedgerEntryView> penalties() {
        return ledger
                .history(student, Optional.of(Currency.AR), Optional.empty(), 100)
                .items()
                .stream()
                .filter(entry -> entry.kind() == EntryKind.PENALTY)
                .toList();
    }

    private void actor(long id, Role role) {
        drainEvents();
        doReturn(new AuthenticatedUser(id, role, "Тест", false, true, null))
                .when(current)
                .require();
    }

    private void drainEvents() {
        await().atMost(Duration.ofSeconds(10))
                .until(
                        () ->
                                jdbc.queryForObject(
                                                "select count(*) from event_publication where completion_date is null",
                                                Integer.class)
                                        == 0);
    }

    private void post(EntryKind kind, int amount, String key, SourceType type, long sourceId) {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        ignored ->
                                ledger.post(
                                        List.of(
                                                new Posting(
                                                        student,
                                                        Currency.AR,
                                                        kind,
                                                        amount,
                                                        new Source(type, sourceId, "Фикстура"),
                                                        null,
                                                        key))));
    }

    private long person(String role) {
        return jdbc.queryForObject(
                "insert into app_user(email,role,full_name) values (?,?,'Тест') returning id",
                Long.class,
                UUID.randomUUID() + "@test.invalid",
                role);
    }

    private long assignment(int cost) {
        long checkpoint =
                jdbc.queryForObject(
                        """
                insert into checkpoint(author_id,title,description,deadline,cost,penalty_step,penalty_interval_seconds,created_at,updated_at)
                values (?,'Работа','Описание',?,?,20,60,?,?) returning id
                """,
                        Long.class,
                        teacher,
                        Timestamp.from(deadline),
                        cost,
                        Timestamp.from(deadline),
                        Timestamp.from(deadline));
        return jdbc.queryForObject(
                """
                insert into assignment(checkpoint_id,student_id,group_id,student_full_name,state,created_at)
                values (?,?,?,'Студент','OPEN',?) returning id
                """,
                Long.class,
                checkpoint,
                student,
                group,
                Timestamp.from(deadline));
    }
}

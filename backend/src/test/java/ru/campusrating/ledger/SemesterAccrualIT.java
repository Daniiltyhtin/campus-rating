package ru.campusrating.ledger;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.campusrating.ledger.internal.SemesterAccrualJob;
import ru.campusrating.support.MutableClock;
import ru.campusrating.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class SemesterAccrualIT extends PostgresIntegrationTest {
    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");
    @Autowired private SemesterAccrualJob job;
    @Autowired private LedgerApi ledger;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MutableClock clock;
    private final List<Long> students = new ArrayList<>();
    private long semester;
    private Long faculty;
    private Long group;

    @BeforeEach
    void createSemester() {
        clock.set(START);
        semester = jdbc.queryForObject("insert into semester (title, starts_at) values (?, ?) returning id",
                Long.class, "Тестовый семестр", Timestamp.from(START));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from semester_accrual where semester_id = ?", semester);
        jdbc.update("delete from semester where id = ?", semester);
        for (long student : students) {
            jdbc.update("delete from semester_accrual where student_id = ?", student);
            jdbc.update("delete from ledger_entry where student_id = ?", student);
            jdbc.update("delete from ledger_balance where student_id = ?", student);
            jdbc.update("delete from group_membership where student_id = ?", student);
            jdbc.update("delete from app_user where id = ?", student);
        }
        if (group != null) {
            jdbc.update("delete from study_group where id = ?", group);
            jdbc.update("delete from faculty where id = ?", faculty);
        }
    }

    @Test
    void ae5CapsAccrualAndRemembersZeroAfterPenalty() {
        long a = student(900);
        long b = student(1000);

        job.runOnce();

        assertThat(ledger.balances(a).ar()).isEqualTo(1000);
        assertAccrual(a, 100, 1);
        assertAccrual(b, 0, 0);
        assertThat(ledger.history(a, Optional.of(Currency.AR), Optional.empty(), 100).items())
                .filteredOn(entry -> entry.kind() == EntryKind.SEMESTER_ACCRUAL)
                .singleElement().satisfies(entry -> {
                    assertThat(entry.source()).isEqualTo(new Source(SourceType.SEMESTER, semester, "Тестовый семестр"));
                    assertThat(entry.author()).isNull();
                });
        post(b, EntryKind.PENALTY, -20, "penalty:" + UUID.randomUUID());

        job.runOnce();

        assertThat(ledger.balances(b).ar()).isEqualTo(980);
        assertAccrual(a, 100, 1);
        assertAccrual(b, 0, 0);
        assertThat(ledger.reconcile().mismatches()).isEmpty();
    }

    @Test
    void futureSemesterWaitsUntilClockReachesStart() {
        long student = student(900);
        clock.set(START.minusMillis(1));

        job.runOnce();

        assertThat(accrualCount(student)).isZero();
        assertThat(ledger.balances(student).ar()).isEqualTo(900);
        clock.set(START);

        job.runOnce();

        assertAccrual(student, 100, 1);
        assertThat(ledger.balances(student).ar()).isEqualTo(1000);
    }

    @Test
    void concurrentPassesPostAndRecordEachStudentOnce() throws Exception {
        long a = student(700);
        long b = student(1000);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> runTogether(barrier));
            var second = executor.submit(() -> runTogether(barrier));
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }
        assertThat(ledger.balances(a).ar()).isEqualTo(900);
        assertAccrual(a, 200, 1);
        assertAccrual(b, 0, 0);
        assertThat(ledger.reconcile().mismatches()).isEmpty();
    }

    @Test
    void pendingMembershipStillReceivesAccrual() {
        long student = student(900);
        faculty = jdbc.queryForObject("insert into faculty (code, name) values (?, ?) returning id",
                Long.class, UUID.randomUUID().toString(), "Факультет");
        group = jdbc.queryForObject("""
                insert into study_group (code, name, faculty_id, admission_year) values (?, ?, ?, 2026) returning id
                """, Long.class, UUID.randomUUID().toString(), "Группа", faculty);
        jdbc.update("insert into group_membership (student_id, group_id, state) values (?, ?, 'PENDING')", student, group);

        job.runOnce();

        assertThat(ledger.balances(student).ar()).isEqualTo(1000);
        assertAccrual(student, 100, 1);
    }

    @Test
    void existingPostingRestoresActualAccrualWithoutPostingAgain() {
        long student = student(900);
        post(student, EntryKind.SEMESTER_ACCRUAL, 200, "semester:" + semester + ":" + student);

        job.runOnce();

        assertAccrual(student, 100, 1);
        assertThat(ledger.balances(student).ar()).isEqualTo(1000);
    }

    @Test
    void processesMoreThanOneBatch() {
        for (int i = 0; i < 501; i++) {
            student(900);
        }

        job.runOnce();

        for (long student : students) {
            assertAccrual(student, 100, 1);
            assertThat(ledger.balances(student).ar()).isEqualTo(1000);
        }
        assertThat(ledger.reconcile().mismatches()).isEmpty();
    }

    @Test
    void failedStudentDoesNotBlockLaterStudentsOrNextPass() {
        long failed = student(700);
        long next = student(700);
        post(failed, EntryKind.PENALTY, -1, "semester:" + semester + ":" + failed);

        job.runOnce();
        job.runOnce();

        assertThat(accrualCount(failed)).isZero();
        assertThat(ledger.balances(failed).ar()).isEqualTo(699);
        assertAccrual(next, 200, 1);
        assertThat(ledger.balances(next).ar()).isEqualTo(900);
        assertThat(ledger.reconcile().mismatches()).isEmpty();
    }

    private void runTogether(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        job.runOnce();
    }

    private long student(int balance) {
        long id = jdbc.queryForObject("insert into app_user (email, role, full_name) values (?, 'STUDENT', ?) returning id",
                Long.class, UUID.randomUUID() + "@example.test", "Студент");
        students.add(id);
        post(id, EntryKind.INITIAL, 1000, "initial:" + id);
        if (balance < 1000) {
            post(id, EntryKind.PENALTY, balance - 1000, "setup:" + id);
        }
        return id;
    }

    private void post(long student, EntryKind kind, int amount, String key) {
        var type = switch (kind) {
            case INITIAL -> SourceType.REGISTRATION;
            case PENALTY -> SourceType.CHECKPOINT;
            case SEMESTER_ACCRUAL -> SourceType.SEMESTER;
            case ACTIVITY_AWARD -> SourceType.ACTIVITY;
        };
        new TransactionTemplate(transactions).executeWithoutResult(status -> ledger.post(List.of(
                new Posting(student, Currency.AR, kind, amount,
                        new Source(type, semester, "Тестовый семестр"), null, key))));
    }

    private int accrualCount(long student) {
        return jdbc.queryForObject("select count(*) from semester_accrual where semester_id = ? and student_id = ?",
                Integer.class, semester, student);
    }

    private void assertAccrual(long student, int amount, int entries) {
        assertThat(accrualCount(student)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select accrued from semester_accrual where semester_id = ? and student_id = ?
                """, Integer.class, semester, student)).isEqualTo(amount);
        assertThat(jdbc.queryForObject("""
                select processed_at from semester_accrual where semester_id = ? and student_id = ?
                """, Timestamp.class, semester, student).toInstant()).isEqualTo(clock.instant());
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where idempotency_key = ?",
                Integer.class, "semester:" + semester + ":" + student)).isEqualTo(entries);
    }
}

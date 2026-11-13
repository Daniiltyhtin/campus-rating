package ru.campusrating.checkpoint;

import ru.campusrating.support.UserNameFixture;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import ru.campusrating.identity.AuthenticatedUser;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.GroupMembershipState;
import ru.campusrating.identity.GroupRef;
import ru.campusrating.identity.MembershipActivated;
import ru.campusrating.identity.Role;
import ru.campusrating.identity.StudentMember;
import ru.campusrating.identity.TeacherScope;
import ru.campusrating.identity.UserRef;
import ru.campusrating.settings.SettingsApi;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;
import ru.campusrating.support.MutableClock;
import ru.campusrating.support.PostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

class CheckpointIT extends PostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @Autowired MutableClock clock;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired PlatformTransactionManager transactions;
    @Autowired IncompleteEventPublications publications;
    @MockitoBean CurrentUser current;
    @MockitoBean TeacherScope scope;
    @MockitoBean GroupMembers members;
    @MockitoBean SettingsApi settings;
    MockMvc mvc;
    long teacher, student, second, pending, group, otherGroup, faculty;
    Instant now;
    final JsonMapper json = new JsonMapper();

    @BeforeEach
    void fixture() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        now = clock.instant();
        faculty =
                jdbc.queryForObject(
                        "insert into faculty(code,name) values (?, 'Факультет') returning id",
                        Long.class,
                        UUID.randomUUID().toString());
        group = group("ИВТ-21");
        otherGroup = group("ИВТ-22");
        teacher = person("TEACHER", "Преподаватель");
        student = person("STUDENT", "Иванов Иван");
        second = person("STUDENT", "Петров Пётр");
        pending = person("STUDENT", "Ожидающий");
        doReturn(new AuthenticatedUser(teacher, Role.TEACHER, "Преподаватель", false, true, null))
                .when(current)
                .require();
        doReturn(20).when(settings).penaltyStep();
        doReturn(Duration.ofMinutes(1)).when(settings).penaltyInterval();
        doReturn(Duration.ofSeconds(2)).when(settings).minPenaltyInterval();
        doReturn(Map.of(group, ref(group, "ИВТ-21"), otherGroup, ref(otherGroup, "ИВТ-22")))
                .when(members)
                .groups(anyCollection());
        doReturn(Map.of(teacher, new UserRef(teacher, "Преподаватель")))
                .when(members)
                .users(anyCollection());
        doReturn(List.of(member(student, group, now), member(second, otherGroup, now)))
                .when(members)
                .activeStudents(anyCollection());
        doReturn(Optional.of(member(student, group, now))).when(members).membership(student);
    }

    @AfterEach
    void cleanup() {
        awaitMembershipPublications();
        awaitCheckpointPublications();
        jdbc.update(
                "delete from submission where assignment_id in (select id from assignment where checkpoint_id in (select id from checkpoint where author_id=?))",
                teacher);
        jdbc.update(
                "delete from assignment where checkpoint_id in (select id from checkpoint where author_id=?)",
                teacher);
        jdbc.update(
                "delete from checkpoint_group where checkpoint_id in (select id from checkpoint where author_id=?)",
                teacher);
        jdbc.update("delete from checkpoint where author_id=?", teacher);
        jdbc.update(
                "delete from event_publication where event_type in (?,?)",
                CheckpointCreated.class.getName(),
                MembershipActivated.class.getName());
        jdbc.update(
                "delete from app_user where id in (?,?,?,?)", teacher, student, second, pending);
        jdbc.update("delete from study_group where id in (?,?)", group, otherGroup);
        jdbc.update("delete from faculty where id=?", faculty);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Covers AE4, AE10: new assignment snapshot uses two parts without trailing space")
    void newSnapshotWithoutMiddleName() throws Exception {
        doReturn(List.of(new StudentMember(student, "Римский-Корсаков Николай", group, GroupMembershipState.ACTIVE, now),
                        member(second, otherGroup, now)))
                .when(members)
                .activeStudents(anyCollection());
        var point = create(now.plusSeconds(600));
        assertThat(jdbc.queryForObject("select student_full_name from assignment where checkpoint_id=? and student_id=?",
                String.class, point.path("id").asLong(), student)).isEqualTo("Римский-Корсаков Николай");
        awaitCheckpointPublications();
    }

    @Test
    void creationSnapshotsMembersAndSettingsBeforeReturning() throws Exception {
        var point = create(now.plusSeconds(600));
        assertThat(point.path("totalCount").asInt()).isEqualTo(2);
        assertThat(point.path("submittedCount").asInt()).isZero();
        long id = point.path("id").asLong();
        assertThat(
                        jdbc.queryForList(
                                "select student_full_name from assignment where checkpoint_id=? order by student_full_name",
                                String.class,
                                id))
                .containsExactly("Иванов Иван", "Петров Пётр");
        assertThat(count(id, pending)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select group_id from assignment where checkpoint_id=? and student_id=?",
                                Long.class,
                                id,
                                student))
                .isEqualTo(group);
        awaitCheckpointPublications();
        doReturn(40).when(settings).penaltyStep();
        doReturn(Duration.ofSeconds(120)).when(settings).penaltyInterval();
        var read = request("GET", "/" + id, null, 200, "TEACHER");
        assertThat(read.path("penaltyStep").asInt()).isEqualTo(20);
        assertThat(read.path("penaltyIntervalSeconds").asInt()).isEqualTo(60);
        assertThat(request("GET", "/defaults", null, 200, "TEACHER").path("penaltyStep").asInt())
                .isEqualTo(40);
    }

    static Stream<Object[]> invalidCreate() {
        return Stream.of(
                new Object[] {"title", " "},
                new Object[] {"title", ""},
                new Object[] {"title", null},
                new Object[] {"title", "x".repeat(201)},
                new Object[] {"description", "x".repeat(10001)},
                new Object[] {"description", null},
                new Object[] {"cost", 0},
                new Object[] {"cost", 1001},
                new Object[] {"cost", 1.5},
                new Object[] {"cost", null},
                new Object[] {"groupIds", List.of()},
                new Object[] {"groupIds", null},
                new Object[] {"deadline", null},
                new Object[] {"deadline", "2020-01-01T00:00:00Z"});
    }

    @ParameterizedTest
    @MethodSource("invalidCreate")
    void validatesCreation(String field, Object value) throws Exception {
        var body = body(now.plusSeconds(600));
        body.put(field, value);
        var error = request("POST", "", json.writeValueAsString(body), 400, "TEACHER");
        assertThat(error.path("fieldErrors").toString()).contains(field);
    }

    @Test
    void duplicateGroupsAndDeadlineEqualityAreInvalid() throws Exception {
        var body = body(now);
        body.put("groupIds", List.of(group, group));
        assertThat(
                        request("POST", "", json.writeValueAsString(body), 400, "TEACHER")
                                .path("fieldErrors")
                                .toString())
                .contains("groupIds");
        body.put("groupIds", List.of(group));
        assertThat(
                        request("POST", "", json.writeValueAsString(body), 400, "TEACHER")
                                .path("fieldErrors")
                                .toString())
                .contains("deadline");
    }

    @Test
    void inaccessibleGroupUsesFirstRequestedNameAndCreatesNothing() throws Exception {
        doThrow(new ApiException(ProblemCode.GROUP_NOT_ACCESSIBLE, "old detail"))
                .when(scope)
                .requireAccessible(eq(teacher), anyCollection());
        doReturn(Set.of()).when(scope).accessibleGroupIds(teacher);
        var body = body(now.plusSeconds(600));
        body.put("groupIds", List.of(otherGroup, group));
        var error = request("POST", "", json.writeValueAsString(body), 403, "TEACHER");
        assertThat(error.path("code").asText()).isEqualTo("GROUP_NOT_ACCESSIBLE");
        assertThat(error.path("detail").asText()).isEqualTo("Группа ИВТ-22 вам недоступна");
    }

    @Test
    void invalidSettingsRejectCreation() throws Exception {
        doReturn(Duration.ofSeconds(1)).when(settings).penaltyInterval();
        assertThat(
                        request(
                                        "POST",
                                        "",
                                        json.writeValueAsString(body(now.plusSeconds(600))),
                                        409,
                                        "TEACHER")
                                .path("code")
                                .asText())
                .isEqualTo("INVALID_STATE");
    }

    @Test
    void authorMayEditAfterLosingScopeAndDeadlineButOthersCannot() throws Exception {
        var original = create(now.plusSeconds(60));
        long id = original.path("id").asLong();
        awaitCheckpointPublications();
        clearInvocations(scope);
        clock.set(now.plusSeconds(120));
        doReturn(Set.of()).when(scope).accessibleGroupIds(teacher);
        var edited = request("PATCH", "/" + id, "{\"title\":\"Новое\"}", 200, "TEACHER");
        assertThat(edited.path("title").asText()).isEqualTo("Новое");
        for (String field :
                List.of(
                        "description",
                        "deadline",
                        "cost",
                        "groups",
                        "penaltyStep",
                        "penaltyIntervalSeconds"))
            assertThat(edited.path(field)).isEqualTo(original.path(field));
        request("GET", "/" + id, null, 200, "TEACHER");
        verifyNoInteractions(scope);
        doReturn(new AuthenticatedUser(teacher + 10000, Role.TEACHER, "Другой", false, true, null))
                .when(current)
                .require();
        request("GET", "/" + id, null, 403, "TEACHER");
        request("PATCH", "/" + id, "{\"title\":\"x\"}", 403, "TEACHER");
        doReturn(new AuthenticatedUser(teacher + 10000, Role.ADMIN, "Админ", false, false, null))
                .when(current)
                .require();
        request("GET", "/" + id, null, 200, "ADMIN");
        request("PATCH", "/" + id, "{\"title\":\"x\"}", 403, "ADMIN");
    }

    @Test
    void patchRejectsEmptyUnknownNullAndInvalidFields() throws Exception {
        long id = create(now.plusSeconds(60)).path("id").asLong();
        for (String body :
                List.of(
                        "{}",
                        "{\"deadline\":\"2030-01-01T00:00:00Z\"}",
                        "{\"title\":\" \"}",
                        "{\"title\":null}",
                        "{\"description\":null}"))
            assertThat(request("PATCH", "/" + id, body, 400, "TEACHER").path("fieldErrors"))
                    .isNotEmpty();
        assertThat(
                        request("PATCH", "/" + id, "{\"deadline\":null}", 400, "TEACHER")
                                .path("fieldErrors")
                                .toString())
                .contains("deadline");
        for (var entry :
                Map.of("title", "x".repeat(201), "description", "x".repeat(10001)).entrySet()) {
            var invalid = json.writeValueAsString(Map.of(entry.getKey(), entry.getValue()));
            assertThat(
                            request("PATCH", "/" + id, invalid, 400, "TEACHER")
                                    .path("fieldErrors")
                                    .toString())
                    .contains(entry.getKey());
        }
        request("PATCH", "/" + id, "{\"description\":\"\"}", 200, "TEACHER");
        request("GET", "/9223372036854775807", null, 404, "TEACHER");
    }

    @Test
    void listSortsFutureThenPastWithCountsAndIdTieBreak() throws Exception {
        long early = create(now.plusSeconds(60)).path("id").asLong();
        long equal = create(now.plusSeconds(60)).path("id").asLong();
        long late = create(now.plusSeconds(120)).path("id").asLong();
        long future = create(now.plusSeconds(240)).path("id").asLong();
        jdbc.update(
                "update assignment set state='SUBMITTED' where checkpoint_id=? and student_id=?",
                late,
                student);
        jdbc.update(
                "update assignment set state='ACCEPTED' where checkpoint_id=? and student_id=?",
                late,
                second);
        clock.set(now.plusSeconds(120));
        var items = request("GET", "", null, 200, "TEACHER").path("items");
        assertThat(items.valueStream().map(n -> n.path("id").asLong()).toList())
                .containsExactly(future, late, early, equal);
        assertThat(items.get(1).path("submittedCount").asInt()).isEqualTo(1);
        assertThat(items.get(1).path("totalCount").asInt()).isEqualTo(2);
    }

    @Test
    void activationAndDelayedCreationUseActivationTimeNotDeliveryTime() throws Exception {
        doReturn(List.of()).when(members).activeStudents(anyCollection());
        long a = create(now.plusSeconds(60)).path("id").asLong();
        long b = create(now.plusSeconds(180)).path("id").asLong();
        awaitCheckpointPublications();
        clock.set(now.plusSeconds(200));
        doReturn(Optional.of(member(student, group, now.plusSeconds(60))))
                .when(members)
                .membership(student);
        publish(new MembershipActivated(student, group, now.plusSeconds(60)));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(count(b, student)).isEqualTo(1));
        awaitMembershipPublications();
        doReturn(List.of(member(student, group, now.plusSeconds(60))))
                .when(members)
                .activeStudents(anyCollection());
        publish(new CheckpointCreated(a));
        publish(new CheckpointCreated(b));
        awaitCheckpointPublications();
        assertThat(count(a, student)).isZero();
        assertThat(count(b, student)).isEqualTo(1);
        doReturn(List.of(member(second, group, now.plusSeconds(61))))
                .when(members)
                .activeStudents(anyCollection());
        publish(new CheckpointCreated(a));
        awaitCheckpointPublications();
        assertThat(count(a, second)).isZero();
    }

    @Test
    void delayedCheckpointListenerExcludesEqualAndLaterActivation() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reads = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (reads.incrementAndGet() == 1) {
                                return List.of();
                            }
                            entered.countDown();
                            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                            return List.of(
                                    member(student, group, now.plusSeconds(60)),
                                    member(second, otherGroup, now.plusSeconds(61)));
                        })
                .when(members)
                .activeStudents(anyCollection());
        long id = create(now.plusSeconds(60)).path("id").asLong();
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            clock.set(now.plusSeconds(120));
        } finally {
            release.countDown();
        }
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "select count(*) from event_publication where event_type=? and completion_date is null",
                                                        Integer.class,
                                                        CheckpointCreated.class.getName()))
                                        .isZero());
        assertThat(count(id, student)).isZero();
        assertThat(count(id, second)).isZero();
    }

    @Test
    void simultaneousActivationAndCreationConvergeInBothCommitOrders() throws Exception {
        for (boolean activationFirst : List.of(true, false)) {
            var bothReading = new CyclicBarrier(2);
            var releaseCreation = new CountDownLatch(1);
            var releaseActivation = new CountDownLatch(1);
            var activated = new AtomicBoolean();
            var firstRead = new AtomicBoolean(true);
            doAnswer(invocation -> {
                if (firstRead.getAndSet(false)) {
                    bothReading.await(10, TimeUnit.SECONDS);
                    assertThat(releaseCreation.await(10, TimeUnit.SECONDS)).isTrue();
                    return List.of();
                }
                return activated.get() ? List.of(member(student, group, now)) : List.of();
            }).when(members).activeStudents(anyCollection());
            try (var executor = Executors.newFixedThreadPool(2)) {
                var creation = executor.submit(() -> create(now.plusSeconds(600)).path("id").asLong());
                var activation = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public int getOrder() {
                            return Integer.MIN_VALUE;
                        }

                        @Override
                        public void afterCommit() {
                            activated.set(true);
                        }
                    });
                    publisher.publishEvent(new MembershipActivated(student, group, now));
                    try {
                        bothReading.await(10, TimeUnit.SECONDS);
                        assertThat(releaseActivation.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                }));
                long id;
                if (activationFirst) {
                    releaseActivation.countDown();
                    activation.get(10, TimeUnit.SECONDS);
                    releaseCreation.countDown();
                    id = creation.get(10, TimeUnit.SECONDS);
                } else {
                    releaseCreation.countDown();
                    id = creation.get(10, TimeUnit.SECONDS);
                    releaseActivation.countDown();
                    activation.get(10, TimeUnit.SECONDS);
                }
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(count(id, student)).isEqualTo(1));
                awaitCheckpointPublications();
                awaitMembershipPublications();
            } finally {
                releaseCreation.countDown();
                releaseActivation.countDown();
            }
        }
    }

    @Test
    void failedListenerRemainsUnfinishedAndRegistryRetryIsIdempotent() throws Exception {
        doReturn(List.of()).when(members).activeStudents(anyCollection());
        long id = create(now.plusSeconds(600)).path("id").asLong();
        awaitCheckpointPublications();
        doThrow(new IllegalStateException("temporary failure")).when(members).membership(student);
        publish(new MembershipActivated(student, group, now));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            assertThat(
                                            jdbc.queryForObject(
                                                    "select count(*) from event_publication where event_type=? and status='FAILED' and completion_date is null",
                                                    Integer.class,
                                                    MembershipActivated.class.getName()))
                                    .isEqualTo(1);
                        });
        verify(members, atLeastOnce()).membership(student);
        assertThat(count(id, student)).isZero();
        doReturn(Optional.of(member(student, group, now))).when(members).membership(student);
        publications.resubmitIncompletePublications(
                publication -> publication.getEvent() instanceof MembershipActivated);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(count(id, student)).isEqualTo(1));
        publish(new MembershipActivated(student, group, now));
        awaitMembershipPublications();
        assertThat(count(id, student)).isEqualTo(1);
    }

    @Test
    void databaseEnforcesUniqueAssignmentsAndState() throws Exception {
        long id = create(now.plusSeconds(600)).path("id").asLong();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "insert into assignment(checkpoint_id,student_id,group_id,student_full_name,state,created_at) values (?,?,?,'Имя','OPEN',?)",
                                        id,
                                        student,
                                        group,
                                        Timestamp.from(now)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update assignment set state='WRONG' where checkpoint_id=?",
                                        id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    void awaitCheckpointPublications() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject(
                    "select count(*) from event_publication where event_type=? and completion_date is null",
                    Integer.class, CheckpointCreated.class.getName())).isZero();
        });
    }

    void awaitMembershipPublications() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject(
                    "select count(*) from event_publication where event_type=? and completion_date is null",
                    Integer.class, MembershipActivated.class.getName())).isZero();
        });
    }

    void publish(Object event) {
        new TransactionTemplate(transactions)
                .executeWithoutResult(s -> publisher.publishEvent(event));
    }

    int count(long checkpoint, long studentId) {
        return jdbc.queryForObject(
                "select count(*) from assignment where checkpoint_id=? and student_id=?",
                Integer.class,
                checkpoint,
                studentId);
    }

    StudentMember member(long id, long groupId, Instant at) {
        return new StudentMember(
                id,
                id == student ? "Иванов Иван" : "Петров Пётр",
                groupId,
                GroupMembershipState.ACTIVE,
                at);
    }

    GroupRef ref(long id, String name) {
        return new GroupRef(id, name, faculty, "ФИТ", "Факультет");
    }

    long group(String name) {
        return jdbc.queryForObject(
                "insert into study_group(code,name,faculty_id,admission_year) values (?,?,?,2026) returning id",
                Long.class,
                UUID.randomUUID().toString(),
                name,
                faculty);
    }

    long person(String role, String name) {
        return jdbc.queryForObject("insert into app_user(email,role,last_name,first_name,middle_name) values (?,?,?,?,?) returning id",
                Long.class,
                UUID.randomUUID() + "@test.invalid",
                role,
                UserNameFixture.last(name),
                UserNameFixture.first(name),
                UserNameFixture.middle(name));
    }

    Map<String, Object> body(Instant deadline) {
        var body = new HashMap<String, Object>();
        body.put("title", "Лабораторная");
        body.put("description", "Описание");
        body.put("groupIds", List.of(group, otherGroup));
        body.put("deadline", deadline.toString());
        body.put("cost", 60);
        return body;
    }

    JsonNode create(Instant deadline) throws Exception {
        return request("POST", "", json.writeValueAsString(body(deadline)), 201, "TEACHER");
    }

    JsonNode request(String method, String path, String body, int status, String role)
            throws Exception {
        var builder =
                MockMvcRequestBuilders.request(
                                HttpMethod.valueOf(method), "/api/v1/checkpoints" + path)
                        .with(user("tester").roles(role))
                        .with(csrf());
        if (body != null) {
            builder.contentType("application/json").content(body);
        }
        var response = mvc.perform(builder).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return json.readTree(response.getContentAsString());
    }
}

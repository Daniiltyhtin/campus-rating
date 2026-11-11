package ru.campusrating.checkpoint;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import ru.campusrating.checkpoint.domain.AssignmentStatusPolicy;
import ru.campusrating.checkpoint.domain.AssignmentCursor;
import ru.campusrating.identity.AuthenticatedUser;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.GroupMembershipState;
import ru.campusrating.identity.GroupRef;
import ru.campusrating.identity.Role;
import ru.campusrating.identity.StudentMember;
import ru.campusrating.identity.TeacherScope;
import ru.campusrating.identity.UserRef;
import ru.campusrating.ledger.Currency;
import ru.campusrating.ledger.EntryKind;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.ledger.Posting;
import ru.campusrating.ledger.Source;
import ru.campusrating.ledger.SourceType;
import ru.campusrating.support.MutableClock;
import ru.campusrating.support.PostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

class AssignmentReadIT extends PostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @Autowired MutableClock clock;
    @Autowired LedgerApi ledger;
    @Autowired StudentAssignments seam;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean CurrentUser current;
    @MockitoBean GroupMembers members;
    @MockitoBean TeacherScope scope;
    final JsonMapper json = new JsonMapper();
    final List<Long> people = new ArrayList<>();
    MockMvc mvc;
    long teacher, student, faculty, group, otherGroup;
    Role role;
    Instant now;

    @BeforeEach
    void fixture() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        now = clock.instant();
        teacher = person(Role.TEACHER, "Преподаватель");
        student = person(Role.STUDENT, "Студент");
        faculty =
                jdbc.queryForObject(
                        "insert into faculty(code,name) values (?,'Факультет') returning id",
                        Long.class,
                        UUID.randomUUID().toString());
        group = group("ИВТ-21");
        otherGroup = group("ИВТ-22");
        doReturn(Map.of(teacher, new UserRef(teacher, "Преподаватель")))
                .when(members)
                .users(anyCollection());
        doReturn(Map.of(group, ref(group, "ИВТ-21"), otherGroup, ref(otherGroup, "ИВТ-22")))
                .when(members)
                .groups(anyCollection());
        membership(GroupMembershipState.ACTIVE);
        actor(student, Role.STUDENT);
        post(EntryKind.INITIAL, 1000);
    }

    @AfterEach
    void cleanup() {
        jdbc.update(
                "delete from submission where assignment_id in (select a.id from assignment a join checkpoint c on c.id=a.checkpoint_id where c.author_id=?)",
                teacher);
        jdbc.update(
                "delete from assignment where checkpoint_id in (select id from checkpoint where author_id=?)",
                teacher);
        jdbc.update(
                "delete from checkpoint_group where checkpoint_id in (select id from checkpoint where author_id=?)",
                teacher);
        jdbc.update("delete from checkpoint where author_id=?", teacher);
        jdbc.update("delete from ledger_entry where student_id=?", student);
        jdbc.update("delete from ledger_balance where student_id=?", student);
        for (long id : people) {
            jdbc.update("delete from app_user where id=?", id);
        }
        jdbc.update("delete from study_group where id in (?,?)", group, otherGroup);
        jdbc.update("delete from faculty where id=?", faculty);
    }

    @Test
    void activeReturnsAllInStatusDeadlineIdOrderAndMatchesSeam() throws Exception {
        long submitted =
                assign(point(now.minusSeconds(300)), student, group, "SUBMITTED", "Студент");
        long future = assign(point(now.plusSeconds(300)), student, group, "OPEN", "Студент");
        long overdue = assign(point(now.minusSeconds(60)), student, group, "OPEN", "Студент");
        long overdueLater = assign(point(now.minusSeconds(30)), student, group, "OPEN", "Студент");
        long tied = assign(point(now.minusSeconds(30)), student, group, "OPEN", "Студент");
        assign(point(now), student, group, "ACCEPTED", "Студент");
        var expected = new ArrayList<>(List.of(overdue, overdueLater, tied, submitted, future));
        for (int i = 0; i < 21; i++) {
            expected.add(
                    assign(point(now.plusSeconds(600 + i)), student, group, "OPEN", "Студент"));
        }
        var page = get("/me/assignments?view=active&limit=1&cursor=ignored", 200);
        assertThat(ids(page)).containsExactlyElementsOf(expected);
        assertThat(ids(get("/me/assignments?view=active&limit=ignored", 200)))
                .containsExactlyElementsOf(expected);
        assertThat(page.path("nextCursor").isNull()).isTrue();
        assertThat(page.path("membershipState").asText()).isEqualTo("ACTIVE");
        assertSeam(page, seam.active(student));
        assertThat(page.at("/items/0/checkpoint/title").asText()).isEqualTo("Работа");
        assertThat(page.at("/items/0/teacher/id").asLong()).isEqualTo(teacher);
        assertThat(page.at("/items/0/penalty/phase").asText()).isEqualTo("RUNNING");
        assertThat(page.at("/items/0/penalty/nextAmount").asInt()).isEqualTo(20);
    }

    @Test
    void completedPagesHaveNoDuplicatesAndMatchSeam() throws Exception {
        var expected = new ArrayList<Long>();
        for (int i = 0; i < 25; i++) {
            long id = assign(point(now), student, group, "ACCEPTED", "Студент");
            jdbc.update(
                    "update assignment set accepted_at=? where id=?",
                    Timestamp.from(now.minusSeconds(i / 3)),
                    id);
            expected.add(id);
        }
        expected.sort(
                Comparator.<Long, Instant>comparing(
                                id ->
                                        jdbc.queryForObject(
                                                        "select accepted_at from assignment where id=?",
                                                        Timestamp.class,
                                                        id)
                                                .toInstant())
                        .thenComparingLong(Long::longValue)
                        .reversed());
        assign(point(now), student, group, "OPEN", "Студент");
        var first = get("/me/assignments?view=completed", 200);
        var second =
                get(
                        "/me/assignments?view=completed&cursor="
                                + first.path("nextCursor").asText(),
                        200);
        assertThat(ids(first)).hasSize(20);
        assertThat(ids(second)).hasSize(5);
        var all = new ArrayList<>(ids(first));
        all.addAll(ids(second));
        assertThat(all).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(second.path("nextCursor").isNull()).isTrue();
        assertThat(seam.completed(student))
                .extracting(StudentAssignmentView::assignmentId)
                .containsExactlyElementsOf(all);
        assertSeam(get("/me/assignments?view=completed&limit=100", 200), seam.completed(student));
    }

    @Test
    void activeMembershipWithoutAssignmentsRemainsActive() throws Exception {
        for (String view : List.of("active", "completed")) {
            var page = get("/me/assignments?view=" + view, 200);
            assertThat(ids(page)).isEmpty();
            assertThat(page.path("membershipState").asText()).isEqualTo("ACTIVE");
            assertThat(page.path("nextCursor").isNull()).isTrue();
        }
    }

    @Test
    void pendingAndAbsentMembershipHideEvenExistingAssignments() throws Exception {
        assign(point(now), student, group, "OPEN", "Студент");
        assign(point(now), student, group, "ACCEPTED", "Студент");
        membership(GroupMembershipState.PENDING);
        assertPending();
        doReturn(Optional.empty()).when(members).membership(student);
        assertPending();
    }

    @Test
    void tablePaginatesByNameAndIdAndKeepsLatestVersionAfterReturn() throws Exception {
        long checkpoint = point(now.plusSeconds(60));
        var expected = new ArrayList<Long>();
        for (int i = 24; i >= 0; i--) {
            long owner = person(Role.STUDENT, "Имя");
            expected.add(
                    assign(
                            checkpoint,
                            owner,
                            group,
                            "OPEN",
                            "Студент " + String.format("%02d", i / 2)));
        }
        expected.sort(
                Comparator.<Long, String>comparing(
                                id ->
                                        jdbc.queryForObject(
                                                "select student_full_name from assignment where id=?",
                                                String.class,
                                                id))
                        .thenComparingLong(Long::longValue));
        long firstId = expected.getFirst();
        jdbc.update(
                "insert into submission(assignment_id,kind,url,submitted_at) values (?,'LINK','https://example.org/old',?)",
                firstId,
                Timestamp.from(now.minusSeconds(1)));
        jdbc.update(
                "insert into submission(assignment_id,kind,file_key,file_name,size_bytes,content_type,submitted_at) values (?,'FILE','key','work.pdf',10,'application/pdf',?)",
                firstId,
                Timestamp.from(now));
        actor(teacher, Role.TEACHER);
        var first = table(checkpoint, "", 200);
        var second = table(checkpoint, "&cursor=" + first.path("nextCursor").asText(), 200);
        assertThat(ids(first)).hasSize(20).containsExactlyElementsOf(expected.subList(0, 20));
        assertThat(ids(second)).containsExactlyElementsOf(expected.subList(20, 25));
        assertThat(second.path("nextCursor").isNull()).isTrue();
        assertThat(first.at("/items/0/submissionKind").asText()).isEqualTo("FILE");
        assertThat(first.at("/items/0/submittedAt").asText()).isEqualTo(now.toString());
        assertThat(first.at("/items/1/submissionKind").isNull()).isTrue();
        assertThat(first.at("/items/0/group/name").asText()).isEqualTo("ИВТ-21");
        assertThat(first.at("/statusCounts/IN_PROGRESS").asInt()).isEqualTo(25);
        assertThat(second.path("statusCounts")).isEqualTo(first.path("statusCounts"));
        actor(teacher, Role.ADMIN);
        assertThat(ids(table(checkpoint, "&limit=100", 200))).containsExactlyElementsOf(expected);
    }

    @Test
    void filtersAndCountsUseGroupButCountsIgnoreStatusAndCursor() throws Exception {
        long checkpoint = point(now.minusSeconds(1));
        long overdue = assign(checkpoint, student, group, "OPEN", "А");
        assign(checkpoint, person(Role.STUDENT, "Б"), group, "SUBMITTED", "Б");
        assign(checkpoint, person(Role.STUDENT, "В"), group, "ACCEPTED", "В");
        assign(checkpoint, person(Role.STUDENT, "Г"), otherGroup, "OPEN", "Г");
        actor(teacher, Role.TEACHER);
        var filtered = table(checkpoint, "&status=OVERDUE&groupId=" + group, 200);
        assertThat(ids(filtered)).containsExactly(overdue);
        assertThat(filtered.path("statusCounts"))
                .isEqualTo(
                        json.readTree(
                                "{\"IN_PROGRESS\":0,\"SUBMITTED\":1,\"OVERDUE\":1,\"ACCEPTED\":1}"));
        assertThat(table(checkpoint, "&status=OVERDUE", 200).at("/statusCounts/OVERDUE").asInt())
                .isEqualTo(2);
        long outsider = group("Внешняя");
        try {
            var empty = table(checkpoint, "&groupId=" + outsider, 200);
            assertThat(ids(empty)).isEmpty();
            assertThat(empty.path("nextCursor").isNull()).isTrue();
            assertThat(empty.path("statusCounts"))
                    .isEqualTo(
                            json.readTree(
                                    "{\"IN_PROGRESS\":0,\"SUBMITTED\":0,\"OVERDUE\":0,\"ACCEPTED\":0}"));
        } finally {
            jdbc.update("delete from study_group where id=?", outsider);
        }
    }

    @Test
    void sqlStatusAndCountsMirrorPolicyAtDeadlineBoundary() throws Exception {
        long checkpoint = point(now);
        long open = assign(checkpoint, student, group, "OPEN", "А");
        assign(checkpoint, person(Role.STUDENT, "Б"), group, "SUBMITTED", "Б");
        assign(checkpoint, person(Role.STUDENT, "В"), group, "ACCEPTED", "В");
        actor(teacher, Role.TEACHER);
        for (long offset : new long[] {-1, 0, 1}) {
            clock.set(now.plusSeconds(offset));
            var expected =
                    AssignmentStatusPolicy.status(AssignmentState.OPEN, now, clock.instant());
            for (AssignmentStatus status : AssignmentStatus.values()) {
                var page = table(checkpoint, "&status=" + status, 200);
                var matching = ids(page);
                if (status == expected) {
                    assertThat(matching).contains(open);
                } else {
                    assertThat(matching).doesNotContain(open);
                }
                for (JsonNode item : page.path("items")) {
                    assertThat(item.path("status").asText()).isEqualTo(status.name());
                    assertThat(item.path("status").asText())
                            .isEqualTo(
                                    AssignmentStatusPolicy.status(
                                                    AssignmentState.valueOf(
                                                            item.path("state").asText()),
                                                    now,
                                                    clock.instant())
                                            .name());
                }
                assertThat(page.path("statusCounts").path(status.name()).asInt())
                        .isEqualTo(matching.size());
            }
            assertThat(get("/assignments/" + open, 200).path("status").asText())
                    .isEqualTo(expected.name());
        }
    }

    @Test
    void penaltyExamplesUseRealLedgerAndAgreeWithSingleRead() throws Exception {
        long checkpoint = point(now);
        long id = assign(checkpoint, student, group, "OPEN", "Студент");
        clock.set(now.minusSeconds(1));
        assertPenalty(id, "BEFORE_DEADLINE", now, 20);
        clock.set(now.plusSeconds(90));
        jdbc.update("update assignment set charged=40,last_penalty_slot=1 where id=?", id);
        post(EntryKind.PENALTY, -40);
        assertThat(ledger.balances(student).ar()).isEqualTo(960);
        assertPenalty(id, "RUNNING", now.plusSeconds(120), 20);
        jdbc.update("update assignment set state='SUBMITTED' where id=?", id);
        assertPenalty(id, "STOPPED", null, null);
        jdbc.update(
                "update assignment set state='OPEN',charged=20,last_penalty_slot=0 where id=?", id);
        post(EntryKind.PENALTY, -960);
        assertPenalty(id, "ZERO_BALANCE", now.plusSeconds(120), 0);
        jdbc.update("update assignment set charged=60 where id=?", id);
        assertPenalty(id, "CAP_REACHED", null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?view=OPEN", "?view=", "?view=Active"})
    void viewIsRequiredAndValidated(String query) throws Exception {
        assertThat(get("/me/assignments" + query, 400).path("fieldErrors").toString())
                .contains("view");
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=101", "limit=x", "cursor=broken"})
    void completedRejectsInvalidPagination(String query) throws Exception {
        String field = query.substring(0, query.indexOf('='));
        assertThat(
                        get("/me/assignments?view=completed&" + query, 400)
                                .path("fieldErrors")
                                .toString())
                .contains(field);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "status=OPEN",
                "status=unknown",
                "status=",
                "groupId=0",
                "limit=0",
                "limit=101",
                "cursor=broken"
            })
    void tableRejectsInvalidFilters(String query) throws Exception {
        actor(teacher, Role.TEACHER);
        assertThat(table(point(now), "&" + query, 400).path("fieldErrors").toString())
                .contains(query.substring(0, query.indexOf('=')));
    }

    @Test
    void completedRejectsWellFormedCursorWithInvalidTimestamp() throws Exception {
        for (String key : List.of("not-a-date", "1969-12-31T23:59:59Z", "+10000-01-01T00:00:00Z")) {
            String cursor = new AssignmentCursor(key, 1, "completed/" + student).encode();
            assertThat(
                            get("/me/assignments?view=completed&cursor=" + cursor, 400)
                                    .path("fieldErrors")
                                    .toString())
                    .contains("cursor");
        }
    }

    @Test
    void cursorsCannotBeReusedForAnotherListOrFilter() throws Exception {
        long checkpoint = point(now);
        assign(checkpoint, student, group, "ACCEPTED", "А");
        assign(checkpoint, person(Role.STUDENT, "Б"), otherGroup, "ACCEPTED", "Б");
        assign(point(now), student, group, "ACCEPTED", "А");
        String completed =
                get("/me/assignments?view=completed&limit=1", 200).path("nextCursor").asText();
        actor(teacher, Role.TEACHER);
        var page = table(checkpoint, "&limit=1", 200);
        String cursor = page.path("nextCursor").asText();
        for (String query : List.of("&status=ACCEPTED", "&groupId=" + group)) {
            assertThat(
                            table(checkpoint, query + "&cursor=" + cursor, 400)
                                    .path("fieldErrors")
                                    .toString())
                    .contains("cursor");
        }
        table(point(now), "&cursor=" + cursor, 400);
        table(checkpoint, "&cursor=" + completed, 400);
        actor(student, Role.STUDENT);
        get("/me/assignments?view=completed&cursor=" + cursor, 400);
        actor(teacher, Role.STUDENT);
        get("/me/assignments?view=completed&cursor=" + completed, 400);
    }

    @Test
    void deniesNonAuthorAndNonStudentAndReportsMissingCheckpoint() throws Exception {
        long checkpoint = point(now);
        actor(student, Role.TEACHER);
        table(checkpoint, "", 403);
        get("/me/assignments?view=active", 403);
        actor(student, Role.STUDENT);
        table(checkpoint, "", 403);
        actor(teacher, Role.ADMIN);
        table(checkpoint, "", 200);
        table(Long.MAX_VALUE, "", 404);
    }

    void assertPending() throws Exception {
        for (String view : List.of("active", "completed")) {
            var page = get("/me/assignments?view=" + view, 200);
            assertThat(ids(page)).isEmpty();
            assertThat(page.path("membershipState").asText()).isEqualTo("PENDING");
            assertThat(page.path("nextCursor").isNull()).isTrue();
        }
        assertThat(seam.active(student)).isEmpty();
        assertThat(seam.completed(student)).isEmpty();
    }

    void assertPenalty(long id, String phase, Instant nextAt, Integer amount) throws Exception {
        var single = get("/assignments/" + id, 200).path("penalty");
        var list = get("/me/assignments?view=active", 200).at("/items/0/penalty");
        assertThat(list).isEqualTo(single);
        assertThat(single.path("phase").asText()).isEqualTo(phase);
        if (nextAt == null) {
            assertThat(single.path("nextAt").isNull()).isTrue();
        } else {
            assertThat(single.path("nextAt").asText()).isEqualTo(nextAt.toString());
        }
        if (amount == null) {
            assertThat(single.path("nextAmount").isNull()).isTrue();
        } else {
            assertThat(single.path("nextAmount").asInt()).isEqualTo(amount);
        }
    }

    void assertSeam(JsonNode page, List<StudentAssignmentView> views) {
        var expected =
                StreamSupport.stream(page.path("items").spliterator(), false)
                        .map(
                                item ->
                                        new StudentAssignmentView(
                                                item.path("assignmentId").asLong(),
                                                item.at("/checkpoint/title").asText(),
                                                Instant.parse(item.path("deadline").asText()),
                                                AssignmentStatus.valueOf(
                                                        item.path("status").asText()),
                                                item.at("/penalty/charged").asInt(),
                                                item.path("acceptedAt").isNull()
                                                        ? null
                                                        : Instant.parse(
                                                                item.path("acceptedAt").asText())))
                        .toList();
        assertThat(views).containsExactlyElementsOf(expected);
    }

    List<Long> ids(JsonNode page) {
        return StreamSupport.stream(page.path("items").spliterator(), false)
                .map(item -> item.path("assignmentId").asLong())
                .toList();
    }

    JsonNode table(long checkpoint, String query, int status) throws Exception {
        return get("/checkpoints/" + checkpoint + "/assignments?" + query, status);
    }

    JsonNode get(String path, int status) throws Exception {
        var response =
                mvc.perform(
                                MockMvcRequestBuilders.get("/api/v1" + path)
                                        .with(user("tester").roles(role.name())))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return json.readTree(response.getContentAsString());
    }

    void actor(long id, Role nextRole) {
        role = nextRole;
        doReturn(new AuthenticatedUser(id, role, "Тест", false, true, null))
                .when(current)
                .require();
    }

    void membership(GroupMembershipState state) {
        doReturn(
                        Optional.of(
                                new StudentMember(
                                        student, "Студент", group, state, now.minusSeconds(600))))
                .when(members)
                .membership(student);
    }

    void post(EntryKind kind, int amount) {
        var type = kind == EntryKind.INITIAL ? SourceType.REGISTRATION : SourceType.CHECKPOINT;
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
                                                        new Source(type, student, "Фикстура"),
                                                        null,
                                                        UUID.randomUUID().toString()))));
    }

    long person(Role personRole, String name) {
        long id =
                jdbc.queryForObject(
                        "insert into app_user(email,role,full_name) values (?,?,?) returning id",
                        Long.class,
                        UUID.randomUUID() + "@test.invalid",
                        personRole.name(),
                        name);
        people.add(id);
        return id;
    }

    long group(String name) {
        return jdbc.queryForObject(
                "insert into study_group(code,name,faculty_id,admission_year) values (?,?,?,2026) returning id",
                Long.class,
                UUID.randomUUID().toString(),
                name,
                faculty);
    }

    GroupRef ref(long id, String name) {
        return new GroupRef(id, name, faculty, "Ф", "Факультет");
    }

    long point(Instant deadline) {
        long id =
                jdbc.queryForObject(
                        "insert into checkpoint(author_id,title,description,deadline,cost,penalty_step,penalty_interval_seconds,created_at,updated_at) values (?,'Работа','Описание',?,60,20,60,?,?) returning id",
                        Long.class,
                        teacher,
                        Timestamp.from(deadline),
                        Timestamp.from(now),
                        Timestamp.from(now));
        jdbc.update(
                "insert into checkpoint_group(checkpoint_id,group_id) values (?,?),(?,?)",
                id,
                group,
                id,
                otherGroup);
        return id;
    }

    long assign(long checkpoint, long owner, long assignedGroup, String state, String name) {
        return jdbc.queryForObject(
                "insert into assignment(checkpoint_id,student_id,group_id,student_full_name,state,accepted_at,created_at) values (?,?,?,?,?,?,?) returning id",
                Long.class,
                checkpoint,
                owner,
                assignedGroup,
                name,
                state,
                state.equals("ACCEPTED") ? Timestamp.from(now) : null,
                Timestamp.from(now));
    }
}

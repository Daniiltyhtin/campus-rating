package ru.campusrating.profile;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import ru.campusrating.identity.AuthenticatedUser;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.Role;
import ru.campusrating.ledger.Author;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** Приёмка U4 (мастер-U10): getUserProfile поверх настоящих швов и БД (П8, KTD6, мастер-R5). */
class ProfileIT extends PostgresIntegrationTest {
    static final String SECRET_URL = "https://secret.example.org/work";
    static final String SECRET_FILE = "secret-work.pdf";
    static final String SECRET_COMMENT = "Переделать вывод, секретный комментарий";

    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @Autowired MutableClock clock;
    @Autowired LedgerApi ledger;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean CurrentUser current;
    final JsonMapper json = new JsonMapper();
    final List<Long> people = new ArrayList<>();
    final List<Long> groups = new ArrayList<>();
    MockMvc mvc;
    Instant now;
    String suffix;
    long faculty, department, group, otherGroup;
    long student, otherStudent, teacher, admin;
    Role role;

    @BeforeEach
    void fixture() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        now = clock.instant();
        suffix = UUID.randomUUID().toString().substring(0, 8);
        faculty = id("insert into faculty(code,name) values (?,'Факультет информационных технологий') returning id", "ФИТ-" + suffix);
        department = id("insert into department(code,name,faculty_id) values (?,'Кафедра программной инженерии',?) returning id",
                "ПИ-" + suffix, faculty);
        group = group("ИВТ-21");
        otherGroup = group("ИВТ-22");
        teacher = person(Role.TEACHER, "Петров Павел Петрович");
        jdbc.update("update app_user set department_id=? where id=?", department, teacher);
        admin = person(Role.ADMIN, "Администратор стенда");
        student = person(Role.STUDENT, "Смирнова Анна Олеговна");
        member(student, group, "ACTIVE");
        otherStudent = person(Role.STUDENT, "Иванов Иван Иванович");
        member(otherStudent, otherGroup, "ACTIVE");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from submission where assignment_id in (select a.id from assignment a join checkpoint c on c.id=a.checkpoint_id where c.author_id=?)", teacher);
        jdbc.update("delete from assignment where checkpoint_id in (select id from checkpoint where author_id=?)", teacher);
        jdbc.update("delete from checkpoint_group where checkpoint_id in (select id from checkpoint where author_id=?)", teacher);
        jdbc.update("delete from checkpoint where author_id=?", teacher);
        for (long id : people) {
            jdbc.update("delete from ledger_entry where student_id=?", id);
            jdbc.update("delete from ledger_balance where student_id=?", id);
            jdbc.update("delete from group_membership where student_id=?", id);
            jdbc.update("delete from app_user where id=?", id);
        }
        for (long id : groups) {
            jdbc.update("delete from study_group where id=?", id);
        }
        jdbc.update("delete from department where id=?", department);
        jdbc.update("delete from faculty where id=?", faculty);
    }

    @Test
    void studentProfileShowsNameGroupFacultyAndBalances() throws Exception {
        post(student, Currency.AR, EntryKind.INITIAL, 1000, new Source(SourceType.REGISTRATION, null, "Стартовые баллы"), null);
        long point = point("Лабораторная 1", now.minusSeconds(3600));
        post(student, Currency.AR, EntryKind.PENALTY, -60, new Source(SourceType.CHECKPOINT, point, "Лабораторная 1"), null);
        var author = new Author(teacher, "Петров Павел Петрович");
        post(student, Currency.BUA, EntryKind.ACTIVITY_AWARD, 120, new Source(SourceType.ACTIVITY, 1L, "Олимпиада"), author);
        post(student, Currency.BOA, EntryKind.ACTIVITY_AWARD, 50, new Source(SourceType.ACTIVITY, 2L, "Конференция"), author);
        actor(otherStudent, Role.STUDENT);

        var profile = profile(student, 200);

        assertThat(profile.path("role").asText()).isEqualTo("STUDENT");
        assertThat(profile.path("id").asLong()).isEqualTo(student);
        assertThat(profile.path("fullName").asText()).isEqualTo("Смирнова Анна Олеговна");
        assertThat(profile.at("/group/id").asLong()).isEqualTo(group);
        assertThat(profile.at("/group/name").asText()).isEqualTo("ИВТ-21");
        assertThat(profile.at("/group/faculty/id").asLong()).isEqualTo(faculty);
        assertThat(profile.at("/group/faculty/code").asText()).isEqualTo("ФИТ-" + suffix);
        assertThat(profile.at("/group/faculty/name").asText()).isEqualTo("Факультет информационных технологий");
        assertThat(profile.path("balances"))
                .isEqualTo(json.readTree("{\"AR\":940,\"BUA\":120,\"BOA\":50}"));
        assertThat(profile.path("activeTasks").isArray()).isTrue();
        assertThat(profile.path("completedTasks").isArray()).isTrue();
    }

    @Test
    void studentWithoutLedgerRowsHasZeroBalancesAndEmptyTasks() throws Exception {
        actor(student, Role.STUDENT);
        var profile = profile(student, 200);
        assertThat(profile.path("balances")).isEqualTo(json.readTree("{\"AR\":0,\"BUA\":0,\"BOA\":0}"));
        assertThat(profile.path("activeTasks")).isEmpty();
        assertThat(profile.path("completedTasks")).isEmpty();
    }

    @Test
    void pendingStudentProfileStillShowsGroup() throws Exception {
        long pending = person(Role.STUDENT, "Новиков Ожидающий");
        member(pending, group, "PENDING");
        actor(student, Role.STUDENT);
        var profile = profile(pending, 200);
        assertThat(profile.path("role").asText()).isEqualTo("STUDENT");
        assertThat(profile.at("/group/id").asLong()).isEqualTo(group);
        assertThat(profile.at("/group/faculty/id").asLong()).isEqualTo(faculty);
    }

    @Test
    void tasksAreOrderedOverdueFirstThenByDeadlineAndCompletedByAcceptedAtDesc() throws Exception {
        // Дедлайн SUBMITTED раньше обоих просроченных: OVERDUE обязан идти первым вопреки дедлайну.
        long submitted = assign(point("Сданная", now.minusSeconds(7200)), student, "SUBMITTED", 0, null);
        long overdueLate = assign(point("Просроченная поздняя", now.minusSeconds(60)), student, "OPEN", 40, null);
        long overdueEarly = assign(point("Просроченная ранняя", now.minusSeconds(3600)), student, "OPEN", 60, null);
        long futureFar = assign(point("Будущая дальняя", now.plusSeconds(86400)), student, "OPEN", 0, null);
        long futureNear = assign(point("Будущая ближняя", now.plusSeconds(600)), student, "OPEN", 0, null);
        // Порядок приёма обратен порядку дедлайнов: сортировка обязана идти по acceptedAt.
        long acceptedMiddle = assign(point("Принята вчера", now.minusSeconds(3 * 86400)), student, "ACCEPTED", 20, now.minusSeconds(86400));
        long acceptedRecent = assign(point("Принята только что", now.minusSeconds(5 * 86400)), student, "ACCEPTED", 0, now.minusSeconds(10));
        long acceptedOld = assign(point("Принята давно", now.minusSeconds(86400)), student, "ACCEPTED", 40, now.minusSeconds(10 * 86400));
        assign(point("Чужая", now.minusSeconds(30)), otherStudent, "OPEN", 0, null);
        actor(student, Role.STUDENT);

        var profile = profile(student, 200);

        assertThat(ids(profile.path("activeTasks")))
                .containsExactly(overdueEarly, overdueLate, submitted, futureNear, futureFar);
        assertThat(statuses(profile.path("activeTasks")))
                .containsExactly("OVERDUE", "OVERDUE", "SUBMITTED", "IN_PROGRESS", "IN_PROGRESS");
        assertThat(ids(profile.path("completedTasks")))
                .containsExactly(acceptedRecent, acceptedMiddle, acceptedOld);
        assertThat(statuses(profile.path("completedTasks"))).containsOnly("ACCEPTED");
        var first = profile.at("/activeTasks/0");
        assertThat(first.path("title").asText()).isEqualTo("Просроченная ранняя");
        assertThat(first.path("deadline").asText()).isEqualTo(now.minusSeconds(3600).toString());
        assertThat(first.path("charged").asInt()).isEqualTo(60);
        assertThat(profile.at("/completedTasks/2/title").asText()).isEqualTo("Принята давно");
        assertThat(profile.at("/completedTasks/2/charged").asInt()).isEqualTo(40);
    }

    @Test
    void foreignProfileShowsPublicTasksWithoutAssignmentId() throws Exception {
        tasksWithSubmissions();
        for (long viewer : List.of(otherStudent, teacher)) {
            actor(viewer, viewer == teacher ? Role.TEACHER : Role.STUDENT);
            var profile = profile(student, 200);
            assertThat(tasks(profile)).hasSize(3);
            for (JsonNode task : tasks(profile)) {
                assertThat(task.path("view").asText()).isEqualTo("PUBLIC");
                assertThat(fields(task)).containsExactlyInAnyOrder("view", "title", "deadline", "status", "charged");
            }
            assertNoSubmissionMaterials(profile);
        }
    }

    @Test
    void ownProfileShowsOwnTasksWithAssignmentId() throws Exception {
        var expected = tasksWithSubmissions();
        actor(student, Role.STUDENT);
        var profile = profile(student, 200);
        assertThat(tasks(profile)).hasSize(expected.size());
        for (JsonNode task : tasks(profile)) {
            assertThat(task.path("view").asText()).isEqualTo("OWN");
            assertThat(fields(task)).containsExactlyInAnyOrder("view", "assignmentId", "title", "deadline", "status", "charged");
        }
        assertThat(tasks(profile).stream().map(task -> task.path("assignmentId").asLong()).toList())
                .containsExactlyInAnyOrderElementsOf(expected);
        assertNoSubmissionMaterials(profile);
    }

    @Test
    void adminSeesAdminTasksWithAssignmentId() throws Exception {
        var expected = tasksWithSubmissions();
        actor(admin, Role.ADMIN);
        var profile = profile(student, 200);
        assertThat(tasks(profile)).hasSize(expected.size());
        for (JsonNode task : tasks(profile)) {
            assertThat(task.path("view").asText()).isEqualTo("ADMIN");
            assertThat(fields(task)).containsExactlyInAnyOrder("view", "assignmentId", "title", "deadline", "status", "charged");
        }
        assertThat(tasks(profile).stream().map(task -> task.path("assignmentId").asLong()).toList())
                .containsExactlyInAnyOrderElementsOf(expected);
        assertNoSubmissionMaterials(profile);
    }

    @Test
    void teacherProfileShowsDepartmentAndFaculty() throws Exception {
        actor(student, Role.STUDENT);
        var profile = profile(teacher, 200);
        assertThat(profile.path("role").asText()).isEqualTo("TEACHER");
        assertThat(profile.path("id").asLong()).isEqualTo(teacher);
        assertThat(profile.path("fullName").asText()).isEqualTo("Петров Павел Петрович");
        assertThat(profile.at("/department/id").asLong()).isEqualTo(department);
        assertThat(profile.at("/department/code").asText()).isEqualTo("ПИ-" + suffix);
        assertThat(profile.at("/department/name").asText()).isEqualTo("Кафедра программной инженерии");
        assertThat(profile.at("/department/faculty/id").asLong()).isEqualTo(faculty);
        assertThat(profile.at("/department/faculty/code").asText()).isEqualTo("ФИТ-" + suffix);
        assertThat(profile.at("/department/faculty/name").asText()).isEqualTo("Факультет информационных технологий");
        assertThat(profile.has("balances")).isFalse();
        assertThat(profile.has("activeTasks")).isFalse();
    }

    @Test
    void adminProfileShowsNameAndRole() throws Exception {
        actor(teacher, Role.TEACHER);
        var profile = profile(admin, 200);
        assertThat(profile.path("role").asText()).isEqualTo("ADMIN");
        assertThat(profile.path("id").asLong()).isEqualTo(admin);
        assertThat(profile.path("fullName").asText()).isEqualTo("Администратор стенда");
        assertThat(profile.has("balances")).isFalse();
        assertThat(profile.has("department")).isFalse();
    }

    @Test
    void ownTeacherAndAdminProfilesAreReadable() throws Exception {
        actor(teacher, Role.TEACHER);
        assertThat(profile(teacher, 200).path("role").asText()).isEqualTo("TEACHER");
        actor(admin, Role.ADMIN);
        assertThat(profile(admin, 200).path("role").asText()).isEqualTo("ADMIN");
    }

    @Test
    void missingUserIsNotFound() throws Exception {
        long removed = person(Role.STUDENT, "Удалённый");
        jdbc.update("delete from app_user where id=?", removed);
        actor(student, Role.STUDENT);
        assertThat(profile(removed, 404).path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(profile(Long.MAX_VALUE, 404).path("code").asText()).isEqualTo("NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    void invalidUserIdIsRejected(String userId) throws Exception {
        actor(student, Role.STUDENT);
        var problem = request(MockMvcRequestBuilders.get("/api/v1/users/" + userId + "/profile"), true, 400);
        assertThat(problem.path("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void guestIsUnauthenticated() throws Exception {
        var problem = request(MockMvcRequestBuilders.get("/api/v1/users/" + student + "/profile"), false, 401);
        assertThat(problem.path("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    /** Активная и завершённая задачи с настоящими сдачами: ссылка, файл и комментарий возврата в БД есть. */
    List<Long> tasksWithSubmissions() {
        long returned = assign(point("Возвращённая", now.plusSeconds(3600)), student, "OPEN", 0, null);
        jdbc.update("insert into submission(assignment_id,kind,url,submitted_at,return_comment,returned_at) values (?,'LINK',?,?,?,?)",
                returned, SECRET_URL, Timestamp.from(now.minusSeconds(600)), SECRET_COMMENT, Timestamp.from(now.minusSeconds(300)));
        long submitted = assign(point("На проверке", now.plusSeconds(7200)), student, "SUBMITTED", 0, null);
        jdbc.update("insert into submission(assignment_id,kind,file_key,file_name,size_bytes,content_type,submitted_at) values (?,'FILE','key-" + suffix + "',?,10,'application/pdf',?)",
                submitted, SECRET_FILE, Timestamp.from(now.minusSeconds(100)));
        long accepted = assign(point("Принятая", now.minusSeconds(86400)), student, "ACCEPTED", 20, now.minusSeconds(3600));
        jdbc.update("insert into submission(assignment_id,kind,url,submitted_at) values (?,'LINK',?,?)",
                accepted, SECRET_URL + "/accepted", Timestamp.from(now.minusSeconds(7200)));
        return List.of(returned, submitted, accepted);
    }

    void assertNoSubmissionMaterials(JsonNode profile) {
        String body = profile.toString();
        assertThat(body).doesNotContain(SECRET_URL, SECRET_FILE, SECRET_COMMENT, "key-" + suffix);
        for (JsonNode task : tasks(profile)) {
            assertThat(fields(task)).doesNotContain("url", "link", "file", "fileName", "submission", "returnComment", "comment");
        }
    }

    List<JsonNode> tasks(JsonNode profile) {
        var all = new ArrayList<JsonNode>();
        profile.path("activeTasks").forEach(all::add);
        profile.path("completedTasks").forEach(all::add);
        return all;
    }

    Set<String> fields(JsonNode node) {
        return Set.copyOf(node.propertyNames());
    }

    List<Long> ids(JsonNode tasks) {
        return StreamSupport.stream(tasks.spliterator(), false).map(task -> task.path("assignmentId").asLong()).toList();
    }

    List<String> statuses(JsonNode tasks) {
        return StreamSupport.stream(tasks.spliterator(), false).map(task -> task.path("status").asText()).toList();
    }

    JsonNode profile(long userId, int status) throws Exception {
        return request(MockMvcRequestBuilders.get("/api/v1/users/" + userId + "/profile"), true, status);
    }

    JsonNode request(MockHttpServletRequestBuilder builder, boolean signedIn, int status) throws Exception {
        if (signedIn) {
            builder.with(user("tester").roles(role.name()));
        }
        var response = mvc.perform(builder).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        String body = response.getContentAsString();
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }

    void actor(long id, Role nextRole) {
        role = nextRole;
        var user = new AuthenticatedUser(id, nextRole, "Смотрящий", false, true, null);
        doReturn(user).when(current).require();
        doReturn(java.util.Optional.of(user)).when(current).find();
    }

    void post(long owner, Currency currency, EntryKind kind, int amount, Source source, Author author) {
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> ledger.post(List.of(
                new Posting(owner, currency, kind, amount, source, author, UUID.randomUUID().toString()))));
    }

    long person(Role personRole, String name) {
        long id = id("insert into app_user(email,role,full_name) values (?,?,?) returning id",
                UUID.randomUUID() + "@test.invalid", personRole.name(), name);
        people.add(id);
        return id;
    }

    void member(long owner, long memberGroup, String state) {
        jdbc.update("insert into group_membership(student_id,group_id,state,activated_at) values (?,?,?,?)",
                owner, memberGroup, state, state.equals("ACTIVE") ? Timestamp.from(now.minusSeconds(86400)) : null);
    }

    long group(String name) {
        long id = id("insert into study_group(code,name,faculty_id,admission_year) values (?,?,?,2026) returning id",
                UUID.randomUUID().toString(), name, faculty);
        groups.add(id);
        return id;
    }

    long point(String title, Instant deadline) {
        long id = id("insert into checkpoint(author_id,title,description,deadline,cost,penalty_step,penalty_interval_seconds,created_at,updated_at) values (?,?,'Описание',?,60,20,60,?,?) returning id",
                teacher, title, Timestamp.from(deadline), Timestamp.from(now.minusSeconds(30 * 86400)), Timestamp.from(now.minusSeconds(30 * 86400)));
        jdbc.update("insert into checkpoint_group(checkpoint_id,group_id) values (?,?),(?,?)", id, group, id, otherGroup);
        return id;
    }

    long assign(long checkpoint, long owner, String state, int charged, Instant acceptedAt) {
        long ownerGroup = owner == student ? group : otherGroup;
        return id("insert into assignment(checkpoint_id,student_id,group_id,student_full_name,state,charged,accepted_at,created_at) values (?,?,?,'Студент',?,?,?,?) returning id",
                checkpoint, owner, ownerGroup, state, charged, acceptedAt == null ? null : Timestamp.from(acceptedAt),
                Timestamp.from(now.minusSeconds(30 * 86400)));
    }

    long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}

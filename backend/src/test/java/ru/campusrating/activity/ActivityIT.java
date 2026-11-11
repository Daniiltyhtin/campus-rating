package ru.campusrating.activity;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.client.RestTestClient;
import ru.campusrating.support.MutableClock;
import ru.campusrating.support.PostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivityIT extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-11T09:00:00Z");
    private static final Instant START = Instant.parse("2026-11-14T07:00:00Z");
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired PasswordEncoder passwords;
    long group, otherGroup, teacher, student, pending, outsider, otherTeacher, unapproved;
    String prefix;
    Browser teacherSession, studentSession, pendingSession, outsiderSession, otherTeacherSession, unapprovedSession;

    @BeforeEach void fixture() throws Exception {
        clock.set(NOW);
        prefix = UUID.randomUUID().toString();
        long faculty = id("insert into faculty(code,name) values (?,?) returning id", prefix, "Факультет");
        long otherFaculty = id("insert into faculty(code,name) values (?,?) returning id", prefix + "other", "Другой");
        group = group(faculty, "A");
        otherGroup = group(otherFaculty, "B");
        long department = id("insert into department(code,name,faculty_id) values (?,?,?) returning id", prefix, "Кафедра", faculty);
        teacher = user("teacher", "TEACHER", department);
        otherTeacher = user("otherTeacher", "TEACHER", department);
        unapproved = user("unapproved", "TEACHER", department);
        update("insert into teacher_approval(teacher_id,approved_at) values (?,?),(?,?)", teacher, NOW, otherTeacher, NOW);
        student = student("student", group, "ACTIVE");
        pending = student("pending", group, "PENDING");
        outsider = student("outsider", otherGroup, "ACTIVE");
        teacherSession = login("teacher"); studentSession = login("student"); pendingSession = login("pending");
        outsiderSession = login("outsider"); otherTeacherSession = login("otherTeacher"); unapprovedSession = login("unapproved");
    }

    @AfterEach void cleanFixture() {
        update("delete from activity_award where author_id=?", teacher);
        update("delete from activity_enrollment where activity_id in (select id from activity where author_id=?)", teacher);
        update("delete from activity_group where activity_id in (select id from activity where author_id=?)", teacher);
        update("delete from activity where author_id=?", teacher);
        update("delete from ledger_entry where idempotency_key=?", prefix);
        update("delete from teacher_grant where teacher_id=?", teacher);
        update("delete from teacher_approval where teacher_id in (?,?)", teacher, otherTeacher);
        update("delete from group_membership where student_id in (?,?,?)", student, pending, outsider);
        update("delete from app_user where email like ?", prefix + "%");
        update("delete from department where code=?", prefix);
        update("delete from study_group where id in (?,?)", group, otherGroup);
        update("delete from faculty where code in (?,?)", prefix, prefix + "other");
    }

    @Test void creationValidatesFieldsScopeAndApproval() throws Exception {
        var valid = create("ACADEMIC", START, group, 75, teacherSession, 201);
        assertThat(valid.path("currency").asText()).isEqualTo("BUA");
        assertThat(valid.path("author").path("id").asLong()).isEqualTo(teacher);
        assertThat(valid.path("groups").get(0).path("faculty").path("name").asText()).isEqualTo("Факультет");
        assertThat(valid.path("enrolled").isNull()).isTrue();
        assertThat(valid.path("myAward").isNull()).isTrue();
        assertThat(valid.path("enrolledCount").asInt()).isZero();
        assertThat(valid.path("confirmedCount").asInt()).isZero();
        assertThat(create("SOCIAL", START, group, 1, teacherSession, 400).path("fieldErrors").get(0).path("field").asText()).isEqualTo("maxPoints");
        assertThat(create("SOCIAL", NOW.minusSeconds(1), group, 50, teacherSession, 400).path("fieldErrors").get(0).path("field").asText()).isEqualTo("startsAt");
        create("SOCIAL", NOW, group, 50, teacherSession, 400);
        problem(create("SOCIAL", START, otherGroup, 50, teacherSession, 403), "GROUP_NOT_ACCESSIBLE");
        create("SOCIAL", START, group, 50, unapprovedSession, 403);
        create("SOCIAL", START, group, 50, studentSession, 403);
    }

    @Test void listFiltersAndOrdersAndReadsPersonalEnrollmentAndAward() throws Exception {
        long near = activity("SOCIAL", START);
        long far = activity("ACADEMIC", START.plusSeconds(3600));
        long recent = activity("SOCIAL", START.plusSeconds(7200));
        long old = activity("SOCIAL", START.plusSeconds(10800));
        update("update activity set starts_at=? where id=?", NOW.minusSeconds(1), recent);
        update("update activity set starts_at=? where id=?", NOW.minusSeconds(3600), old);
        create("SOCIAL", START, otherGroup, 50, grantOtherGroup(), 201);
        request(put(path(near) + "/enrollment"), studentSession, 200);
        long entry = id("""
                insert into ledger_entry(student_id,currency,kind,amount,source_type,source_id,source_title,author_id,
                author_name,idempotency_key,created_at) values (?,'BOA','ACTIVITY_AWARD',38,'ACTIVITY',?,'Activity',?,
                'Преподаватель',?,?) returning id
                """, student, recent, teacher, prefix, NOW);
        update("""
                insert into activity_award(activity_id,student_id,group_id,level,amount,currency,author_id,confirmed_at,ledger_entry_id)
                values (?,?,?,50,38,'BOA',?,?,?)
                """, recent, student, group, teacher, NOW, entry);
        var items = request(get("/api/v1/activities"), studentSession, 200).path("items");
        assertThat(items).hasSize(4);
        assertThat(items.get(0).path("id").asLong()).isEqualTo(near);
        assertThat(items.get(1).path("id").asLong()).isEqualTo(far);
        assertThat(items.get(2).path("id").asLong()).isEqualTo(recent);
        assertThat(items.get(3).path("id").asLong()).isEqualTo(old);
        assertThat(items.get(0).path("enrolled").asBoolean()).isTrue();
        assertThat(items.get(1).path("enrolled").isNull()).isTrue();
        assertThat(items.get(2).path("myAward").path("amount").asInt()).isEqualTo(38);
        assertThat(items.get(2).path("myAward").path("level").asInt()).isEqualTo(50);
        assertThat(items.get(2).path("confirmedCount").asInt()).isEqualTo(1);
        var pendingItems = request(get("/api/v1/activities"), pendingSession, 200).path("items");
        assertThat(pendingItems).hasSize(4);
        assertThat(pendingItems.get(0).path("enrolled").asBoolean()).isFalse();
        assertThat(pendingItems.get(2).path("myAward").isNull()).isTrue();
    }

    @Test void authorRetainsReadAccessAfterScopeLossAndOthersAreForbidden() throws Exception {
        long activity = activity("SOCIAL", START);
        update("delete from teacher_approval where teacher_id=?", teacher);
        assertThat(request(get("/api/v1/activities"), teacherSession, 200).path("items")).hasSize(1);
        request(get(path(activity)), teacherSession, 200);
        assertThat(request(get("/api/v1/activities"), otherTeacherSession, 200).path("items")).isEmpty();
        problem(request(get(path(activity)), otherTeacherSession, 403), "FORBIDDEN");
        problem(request(get(path(activity)), outsiderSession, 403), "FORBIDDEN");
        request(get(path(activity)), pendingSession, 200);
        problem(request(get(path(Long.MAX_VALUE)), studentSession, 404), "NOT_FOUND");
        request(get(path(activity)), null, 401);
    }

    @Test void enrollmentIsIdempotentAndChecksTypeMembershipAndGroup() throws Exception {
        long social = activity("SOCIAL", START);
        var first = request(put(path(social) + "/enrollment"), studentSession, 200);
        assertThat(first.path("enrolled").asBoolean()).isTrue();
        assertThat(first.path("enrollmentOpen").asBoolean()).isTrue();
        assertThat(first.path("confirmationOpen").asBoolean()).isFalse();
        var enrolledAt = jdbc.queryForObject("select enrolled_at from activity_enrollment where activity_id=? and student_id=?", Timestamp.class, social, student);
        clock.set(NOW.plusSeconds(1));
        assertThat(request(put(path(social) + "/enrollment"), studentSession, 200).path("enrolledCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select enrolled_at from activity_enrollment where activity_id=? and student_id=?", Timestamp.class, social, student)).isEqualTo(enrolledAt);
        problem(request(put(path(social) + "/enrollment"), pendingSession, 422), "MEMBERSHIP_NOT_ACTIVE");
        problem(request(put(path(social) + "/enrollment"), outsiderSession, 403), "FORBIDDEN");
        long academic = activity("ACADEMIC", START);
        problem(request(put(path(academic) + "/enrollment"), studentSession, 409), "INVALID_STATE");
        problem(request(delete(path(academic) + "/enrollment"), studentSession, 409), "INVALID_STATE");
    }

    @Test void cancellationIsIdempotentAndDoesNotRequireActiveMembership() throws Exception {
        long social = activity("SOCIAL", START);
        request(put(path(social) + "/enrollment"), studentSession, 200);
        update("update group_membership set state='PENDING',activated_at=null where student_id=?", student);
        var result = request(delete(path(social) + "/enrollment"), studentSession, 200);
        assertThat(result.path("enrolled").asBoolean()).isFalse();
        assertThat(result.path("enrolledCount").asInt()).isZero();
        request(delete(path(social) + "/enrollment"), studentSession, 200);
        problem(request(delete(path(social) + "/enrollment"), outsiderSession, 403), "FORBIDDEN");
    }

    @Test void exactStartAndAfterRejectEnrollmentAndCancellation() throws Exception {
        long social = activity("SOCIAL", START);
        request(put(path(social) + "/enrollment"), studentSession, 200);
        for (var instant : new Instant[]{START, START.plusSeconds(1)}) {
            clock.set(instant);
            problem(request(put(path(social) + "/enrollment"), studentSession, 422), "ENROLLMENT_CLOSED");
            problem(request(delete(path(social) + "/enrollment"), studentSession, 422), "ENROLLMENT_CLOSED");
            var view = request(get(path(social)), studentSession, 200);
            assertThat(view.path("enrollmentOpen").asBoolean()).isFalse();
            assertThat(view.path("confirmationOpen").asBoolean()).isTrue();
            assertThat(view.path("enrolledCount").asInt()).isEqualTo(1);
        }
    }

    @Test void schemaRejectsInvalidTypesLevelsCurrencyPointsAndSpent() throws Exception {
        long activity = activity("SOCIAL", START);
        assertThatThrownBy(() -> update("update activity set type='OTHER' where id=?", activity))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (int maximum : new int[]{1, 10001}) {
            assertThatThrownBy(() -> update("update activity set max_points=? where id=?", maximum, activity))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> update("insert into activity_budget(teacher_id,group_id,month,spent) values (?,?,'2026-11-01',-1)", teacher, group))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (Object[] invalid : new Object[][]{{30, "BOA"}, {50, "AR"}}) {
            assertThatThrownBy(() -> update("""
                    insert into activity_award(activity_id,student_id,group_id,level,amount,currency,author_id,confirmed_at)
                    values (?,?,?,?,38,?,?,?)
                    """, activity, student, group, invalid[0], invalid[1], teacher, NOW))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> update("insert into activity_group(activity_id,group_id) values (?,?)", activity, Long.MAX_VALUE))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Browser grantOtherGroup() {
        update("insert into teacher_grant(teacher_id,kind,group_id) values (?,'GROUP',?)", teacher, otherGroup);
        return teacherSession;
    }
    private long activity(String type, Instant start) throws Exception { return create(type, start, group, 75, teacherSession, 201).path("id").asLong(); }
    private JsonNode create(String type, Instant start, long target, int maximum, Browser session, int status) throws Exception {
        return request(post("/api/v1/activities").content(JSON.writeValueAsString(Map.of("title", "Activity", "description", "",
                "type", type, "startsAt", start.toString(), "groupIds", new long[]{target}, "maxPoints", maximum))), session, status);
    }
    private String path(long id) { return "/api/v1/activities/" + id; }
    private void problem(JsonNode result, String code) { assertThat(result.path("code").asText()).isEqualTo(code); }
    private record Request(String method, String path, String body) {
        Request content(String body) { return new Request(method, path, body); }
    }
    private static Request get(String path) { return new Request("GET", path, null); }
    private static Request post(String path) { return new Request("POST", path, null); }
    private static Request put(String path) { return new Request("PUT", path, null); }
    private static Request delete(String path) { return new Request("DELETE", path, null); }
    private final class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final RestTestClient client;
        Browser() {
            var http = HttpClient.newBuilder().cookieHandler(cookies).build();
            client = RestTestClient.bindToServer(
                    new JdkClientHttpRequestFactory(http))
                    .baseUrl("http://127.0.0.1:" + port).build();
        }
    }
    private JsonNode request(Request request, Browser browser, int status) {
        if (browser == null) browser = new Browser();
        boolean mutation = !request.method().equals("GET");
        if (mutation) browser.client.get().uri("/api/v1/auth/csrf").exchange().expectStatus().isNoContent();
        var call = browser.client.method(HttpMethod.valueOf(request.method())).uri(request.path())
                .contentType(MediaType.APPLICATION_JSON);
        if (mutation) {
            var token = browser.cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow();
            call.header("X-XSRF-TOKEN", URLDecoder.decode(token.getValue(), StandardCharsets.UTF_8));
        }
        if (request.body() != null) call.body(request.body());
        var result = call.exchange().returnResult(String.class);
        assertThat(result.getStatus().value()).withFailMessage(result.getResponseBody()).isEqualTo(status);
        return JSON.readTree(result.getResponseBody());
    }
    private Browser login(String name) {
        var browser = new Browser();
        request(post("/api/v1/auth/login").content(JSON.writeValueAsString(Map.of("email", prefix + name + "@example.test",
                "password", "Password-42"))), browser, 200);
        return browser;
    }
    private long user(String name, String role, Long department) {
        return id("insert into app_user(email,role,full_name,password_hash,department_id) values (?,?,?,?,?) returning id",
                prefix + name + "@example.test", role, name, passwords.encode("Password-42"), department);
    }
    private long student(String name, long target, String state) {
        long id = user(name, "STUDENT", null);
        update("insert into group_membership(student_id,group_id,state,activated_at) values (?,?,?,?)", id, target, state,
                state.equals("ACTIVE") ? NOW.minusSeconds(3600) : null);
        return id;
    }
    private long group(long faculty, String suffix) {
        return id("insert into study_group(code,name,faculty_id,admission_year) values (?,?,?,2026) returning id", prefix + suffix, suffix, faculty);
    }
    private Object[] parameters(Object[] args) {
        return Arrays.stream(args).map(value -> value instanceof Instant instant ? Timestamp.from(instant) : value).toArray();
    }
    private void update(String sql, Object... args) { jdbc.update(sql, parameters(args)); }
    private long id(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, parameters(args)); }
}

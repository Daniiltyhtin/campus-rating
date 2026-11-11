package ru.campusrating.activity;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
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

abstract class ActivityTestSupport extends PostgresIntegrationTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant NOW = Instant.parse("2026-11-11T09:00:00Z");
    static final Instant START = Instant.parse("2026-11-14T07:00:00Z");

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MutableClock clock;
    @Autowired
    PasswordEncoder passwords;

    long group;
    long secondGroup;
    long otherGroup;
    long teacher;
    long otherTeacher;
    long unapproved;
    long student;
    long classmate;
    long neighbour;
    long pending;
    long outsider;
    String prefix;
    Browser teacherSession;
    Browser otherTeacherSession;
    Browser unapprovedSession;
    Browser studentSession;
    Browser pendingSession;
    Browser outsiderSession;

    @BeforeEach
    void fixture() {
        clock.set(NOW);
        prefix = UUID.randomUUID().toString();
        long faculty = id("insert into faculty(code,name) values (?,?) returning id", prefix, "Факультет");
        long otherFaculty = id("insert into faculty(code,name) values (?,?) returning id", prefix + "other", "Другой");
        group = group(faculty, "A");
        secondGroup = group(faculty, "C");
        otherGroup = group(otherFaculty, "B");
        long department = id("insert into department(code,name,faculty_id) values (?,?,?) returning id", prefix, "Кафедра", faculty);
        teacher = user("teacher", "TEACHER", department);
        otherTeacher = user("otherTeacher", "TEACHER", department);
        unapproved = user("unapproved", "TEACHER", department);
        update("insert into teacher_approval(teacher_id,approved_at) values (?,?),(?,?)", teacher, NOW, otherTeacher, NOW);
        student = student("student", group, "ACTIVE");
        classmate = student("classmate", group, "ACTIVE");
        neighbour = student("neighbour", secondGroup, "ACTIVE");
        pending = student("pending", group, "PENDING");
        outsider = student("outsider", otherGroup, "ACTIVE");
        teacherSession = login("teacher");
        otherTeacherSession = login("otherTeacher");
        unapprovedSession = login("unapproved");
        studentSession = login("student");
        pendingSession = login("pending");
        outsiderSession = login("outsider");
    }

    @AfterEach
    void cleanFixture() {
        update("delete from settings where key='monthlyBudgetLimit'");
        update("delete from activity_award where author_id in (?,?)", teacher, otherTeacher);
        update("delete from activity_budget where teacher_id in (?,?)", teacher, otherTeacher);
        update("delete from activity_enrollment where activity_id in (select id from activity where author_id in (?,?))", teacher, otherTeacher);
        update("delete from activity_group where activity_id in (select id from activity where author_id in (?,?))", teacher, otherTeacher);
        update("delete from activity where author_id in (?,?)", teacher, otherTeacher);
        for (long id : students()) {
            update("delete from ledger_entry where student_id=?", id);
            update("delete from ledger_balance where student_id=?", id);
        }
        update("delete from teacher_grant where teacher_id=?", teacher);
        update("delete from teacher_approval where teacher_id in (?,?)", teacher, otherTeacher);
        for (long id : students()) {
            update("delete from group_membership where student_id=?", id);
        }
        update("delete from app_user where email like ?", prefix + "%");
        update("delete from department where code=?", prefix);
        update("delete from study_group where id in (?,?,?)", group, secondGroup, otherGroup);
        update("delete from faculty where code in (?,?)", prefix, prefix + "other");
    }

    List<Long> students() {
        return List.of(student, classmate, neighbour, pending, outsider);
    }

    Browser grantOtherGroup() {
        update("insert into teacher_grant(teacher_id,kind,group_id) values (?,'GROUP',?)", teacher, otherGroup);
        return teacherSession;
    }

    long activity(String type, Instant start) {
        return create(type, start, group, 75, teacherSession, 201).path("id").asLong();
    }

    long activity(String type, Instant start, int maxPoints, long... groups) {
        return create(type, start, groups, maxPoints, teacherSession, 201).path("id").asLong();
    }

    JsonNode create(String type, Instant start, long target, int maximum, Browser session, int status) {
        return create(type, start, new long[]{target}, maximum, session, status);
    }

    JsonNode create(String type, Instant start, long[] groups, int maximum, Browser session, int status) {
        var body = Map.of("title", "Activity", "description", "", "type", type, "startsAt", start.toString(),
                "groupIds", groups, "maxPoints", maximum);
        return request(post("/api/v1/activities").content(JSON.writeValueAsString(body)), session, status);
    }

    String path(long id) {
        return "/api/v1/activities/" + id;
    }

    void problem(JsonNode result, String code) {
        assertThat(result.path("code").asText()).isEqualTo(code);
    }

    record Request(String method, String path, String body) {
        Request content(String body) {
            return new Request(method, path, body);
        }
    }

    static Request get(String path) {
        return new Request("GET", path, null);
    }

    static Request post(String path) {
        return new Request("POST", path, null);
    }

    static Request put(String path) {
        return new Request("PUT", path, null);
    }

    static Request delete(String path) {
        return new Request("DELETE", path, null);
    }

    final class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final RestTestClient client;

        Browser() {
            var http = HttpClient.newBuilder().cookieHandler(cookies).build();
            client = RestTestClient.bindToServer(new JdkClientHttpRequestFactory(http))
                    .baseUrl("http://127.0.0.1:" + port)
                    .build();
        }
    }

    JsonNode request(Request request, Browser session, int status) {
        var result = exchange(request, session);
        assertThat(result.status()).withFailMessage(result.body()).isEqualTo(status);
        return JSON.readTree(result.body());
    }

    record Response(int status, String body) {}

    Response exchange(Request request, Browser session) {
        var browser = session == null ? new Browser() : session;
        boolean mutation = !request.method().equals("GET");
        if (mutation) {
            browser.client.get().uri("/api/v1/auth/csrf").exchange().expectStatus().isNoContent();
        }
        var call = browser.client.method(HttpMethod.valueOf(request.method()))
                .uri(request.path())
                .contentType(MediaType.APPLICATION_JSON);
        if (mutation) {
            var token = browser.cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                    .findFirst()
                    .orElseThrow();
            call.header("X-XSRF-TOKEN", URLDecoder.decode(token.getValue(), StandardCharsets.UTF_8));
        }
        if (request.body() != null) {
            call.body(request.body());
        }
        var result = call.exchange().returnResult(String.class);
        return new Response(result.getStatus().value(), result.getResponseBody());
    }

    Browser login(String name) {
        var browser = new Browser();
        var body = Map.of("email", prefix + name + "@example.test", "password", "Password-42");
        request(post("/api/v1/auth/login").content(JSON.writeValueAsString(body)), browser, 200);
        return browser;
    }

    long user(String name, String role, Long department) {
        return id("insert into app_user(email,role,full_name,password_hash,department_id) values (?,?,?,?,?) returning id",
                prefix + name + "@example.test", role, name, passwords.encode("Password-42"), department);
    }

    long student(String name, long target, String state) {
        long id = user(name, "STUDENT", null);
        Instant activatedAt = state.equals("ACTIVE") ? NOW.minusSeconds(3600) : null;
        update("insert into group_membership(student_id,group_id,state,activated_at) values (?,?,?,?)", id, target, state, activatedAt);
        return id;
    }

    long group(long faculty, String suffix) {
        return id("insert into study_group(code,name,faculty_id,admission_year) values (?,?,?,2026) returning id",
                prefix + suffix, suffix, faculty);
    }

    private Object[] parameters(Object[] args) {
        return Arrays.stream(args)
                .map(value -> value instanceof Instant instant ? Timestamp.from(instant) : value)
                .toArray();
    }

    void update(String sql, Object... args) {
        jdbc.update(sql, parameters(args));
    }

    long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, parameters(args));
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, parameters(args));
    }
}

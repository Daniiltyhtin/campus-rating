package ru.campusrating.feed;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.util.LinkedMultiValueMap;
import ru.campusrating.support.MutableClock;
import ru.campusrating.support.PostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

abstract class PersonalFeedSupport extends PostgresIntegrationTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String FEED = "/api/v1/feed/personal";
    static final Instant NOW = Instant.parse("2026-11-18T09:00:00Z");
    static final Instant ESSAY_DEADLINE = Instant.parse("2026-11-20T15:00:00Z");
    static final String TEACHER_NAME = "Петров Павел Петрович";
    static final String STUDENT_NAME = "Смирнова Анна Олеговна";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired PasswordEncoder passwords;

    final List<Long> users = new ArrayList<>();
    String prefix;
    String passwordHash;
    long faculty;
    long group;
    long otherGroup;
    long department;
    long teacher;
    long student;
    long classmate;
    long pending;
    long neighbour;
    long admin;
    Browser teacherSession;
    Browser studentSession;

    @BeforeEach
    void personalFeedFixture() {
        clock.set(NOW);
        prefix = UUID.randomUUID().toString();
        passwordHash = passwords.encode("Password-42");
        faculty = id("insert into faculty(code,name) values (?,?) returning id", prefix, "Факультет ленты");
        group = id("insert into study_group(code,name,faculty_id,admission_year) values (?,?,?,2026) returning id",
                prefix + "-21", "ИВТ-21", faculty);
        otherGroup = id("insert into study_group(code,name,faculty_id,admission_year) values (?,?,?,2026) returning id",
                prefix + "-22", "ИВТ-22", faculty);
        department = id("insert into department(code,name,faculty_id) values (?,?,?) returning id", prefix, "Кафедра", faculty);
        teacher = user("teacher", "TEACHER", TEACHER_NAME, department);
        update("insert into teacher_approval(teacher_id,approved_at) values (?,?)", teacher, NOW);
        admin = user("admin", "ADMIN", "Администратор", null);
        student = student("student", STUDENT_NAME, group, "ACTIVE");
        classmate = student("classmate", "Кузнецов Дмитрий Андреевич", group, "ACTIVE");
        pending = student("pending", "Ожидающий Олег Олегович", group, "PENDING");
        neighbour = student("neighbour", "Иванов Иван Иванович", otherGroup, "ACTIVE");
        teacherSession = login("teacher");
        studentSession = login("student");
    }

    @AfterEach
    void cleanPersonalFeedFixture() {
        drainEvents();
        String points = "select id from checkpoint where author_id=" + teacher;
        update("delete from submission where assignment_id in (select id from assignment where checkpoint_id in (" + points + "))");
        update("delete from assignment where checkpoint_id in (" + points + ")");
        update("delete from checkpoint_group where checkpoint_id in (" + points + ")");
        update("delete from checkpoint where author_id=?", teacher);
        update("delete from activity_award where author_id=?", teacher);
        update("delete from activity_budget where teacher_id=?", teacher);
        String activities = "select id from activity where author_id=" + teacher;
        update("delete from activity_enrollment where activity_id in (" + activities + ")");
        update("delete from activity_group where activity_id in (" + activities + ")");
        update("delete from activity where author_id=?", teacher);
        for (long user : users) {
            update("delete from user_event where recipient_id=?", user);
            update("delete from ledger_entry where student_id=?", user);
            update("delete from ledger_balance where student_id=?", user);
            update("delete from group_membership where student_id=?", user);
            update("delete from teacher_approval where teacher_id=?", user);
        }
        for (long user : users) {
            update("delete from app_user where id=?", user);
        }
        update("delete from department where id=?", department);
        update("delete from study_group where id in (?,?)", group, otherGroup);
        update("delete from faculty where id=?", faculty);
    }

    void drainEvents() {
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject("""
                select count(*) from event_publication
                where completion_date is null and coalesce(status, '') <> 'FAILED'
                """, Integer.class) == 0);
    }

    long checkpoint(String title, Instant deadline, long... groups) {
        var body = Map.of("title", title, "description", "Описание", "deadline", deadline.toString(), "cost", 60,
                "groupIds", Arrays.stream(groups).boxed().toList());
        return request(post("/api/v1/checkpoints").content(JSON.writeValueAsString(body)), teacherSession, 201)
                .path("id").asLong();
    }

    long assignment(long checkpointId, long studentId) {
        await().atMost(Duration.ofSeconds(10)).until(() -> count(
                "select count(*) from assignment where checkpoint_id=? and student_id=?", checkpointId, studentId) == 1);
        return id("select id from assignment where checkpoint_id=? and student_id=?", checkpointId, studentId);
    }

    void submit(long assignmentId, Browser session) {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("link", "https://example.org/" + UUID.randomUUID());
        var result = multipart("/api/v1/assignments/" + assignmentId + "/submission", parts, session);
        assertThat(result.status()).withFailMessage(result.body()).isEqualTo(200);
    }

    void returnWork(long assignmentId) {
        request(post("/api/v1/assignments/" + assignmentId + "/return")
                .content(JSON.writeValueAsString(Map.of("comment", "Доработайте"))), teacherSession, 200);
    }

    JsonNode accept(long checkpointId, List<Long> assignmentIds) {
        return request(post("/api/v1/checkpoints/" + checkpointId + "/assignments/accept")
                .content(JSON.writeValueAsString(Map.of("assignmentIds", assignmentIds))), teacherSession, 200);
    }

    long activity(String title, String type, Instant startsAt, int maxPoints, long... groups) {
        var body = Map.of("title", title, "description", "", "type", type, "startsAt", startsAt.toString(),
                "groupIds", Arrays.stream(groups).boxed().toList(), "maxPoints", maxPoints);
        return request(post("/api/v1/activities").content(JSON.writeValueAsString(body)), teacherSession, 201)
                .path("id").asLong();
    }

    Response confirm(long activityId, long studentId, int level) {
        var body = Map.of("items", List.of(Map.of("studentId", studentId, "level", level)));
        return exchange(post("/api/v1/activities/" + activityId + "/participation").content(JSON.writeValueAsString(body)),
                teacherSession);
    }

    List<JsonNode> feed(Browser session) {
        var items = new ArrayList<JsonNode>();
        String cursor = null;
        do {
            String path = FEED + "?limit=100" + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            var page = request(get(path), session, 200);
            page.path("items").forEach(items::add);
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        } while (cursor != null);
        return items;
    }

    List<JsonNode> awaitFeed(Browser session, int size) {
        await().atMost(Duration.ofSeconds(10)).until(() -> feed(session).size() >= size);
        drainEvents();
        var items = feed(session);
        assertThat(items).hasSize(size);
        return items;
    }

    List<JsonNode> ofKind(List<JsonNode> items, String kind) {
        return items.stream().filter(item -> item.path("kind").asText().equals(kind)).toList();
    }

    int events(long recipient) {
        return count("select count(*) from user_event where recipient_id=?", recipient);
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

    static Request patch(String path) {
        return new Request("PATCH", path, null);
    }

    static Request put(String path) {
        return new Request("PUT", path, null);
    }

    final class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final RestTestClient client;

        Browser() {
            var http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
            client = RestTestClient.bindToServer(new JdkClientHttpRequestFactory(http))
                    .baseUrl("http://127.0.0.1:" + port)
                    .build();
        }

        String csrf() {
            client.get().uri("/api/v1/auth/csrf").exchange().expectStatus().isNoContent();
            var token = cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                    .findFirst()
                    .orElseThrow();
            return URLDecoder.decode(token.getValue(), StandardCharsets.UTF_8);
        }
    }

    record Response(int status, String body) {}

    JsonNode request(Request request, Browser session, int status) {
        var result = exchange(request, session);
        assertThat(result.status()).withFailMessage(result.body()).isEqualTo(status);
        return JSON.readTree(result.body());
    }

    Response exchange(Request request, Browser session) {
        var browser = session == null ? new Browser() : session;
        var call = browser.client.method(HttpMethod.valueOf(request.method()))
                .uri(request.path())
                .contentType(MediaType.APPLICATION_JSON);
        if (!request.method().equals("GET")) {
            call.header("X-XSRF-TOKEN", browser.csrf());
        }
        if (request.body() != null) {
            call.body(request.body());
        }
        var result = call.exchange().returnResult(String.class);
        return new Response(result.getStatus().value(), result.getResponseBody());
    }

    Response multipart(String path, LinkedMultiValueMap<String, Object> parts, Browser session) {
        var call = session.client.post().uri(path).contentType(MediaType.MULTIPART_FORM_DATA)
                .header("X-XSRF-TOKEN", session.csrf()).body(parts);
        var result = call.exchange().returnResult(String.class);
        return new Response(result.getStatus().value(), result.getResponseBody());
    }

    Browser login(String name) {
        var browser = new Browser();
        var body = Map.of("email", prefix + name + "@example.test", "password", "Password-42");
        request(post("/api/v1/auth/login").content(JSON.writeValueAsString(body)), browser, 200);
        return browser;
    }

    long register(String name, String fullName, long targetGroup) {
        var body = Map.of("fullName", fullName, "email", prefix + name + "@example.test", "password", "Password-42",
                "facultyId", faculty, "groupId", targetGroup);
        long id = request(post("/api/v1/auth/register/student").content(JSON.writeValueAsString(body)), null, 201)
                .path("userId").asLong();
        users.add(id);
        return id;
    }

    long user(String name, String role, String fullName, Long departmentId) {
        long result = id("insert into app_user(email,role,full_name,password_hash,department_id) values (?,?,?,?,?) returning id",
                prefix + name + "@example.test", role, fullName, passwordHash, departmentId);
        users.add(result);
        return result;
    }

    long student(String login, String fullName, long target, String state) {
        long result = user(login, "STUDENT", fullName, null);
        update("insert into group_membership(student_id,group_id,state,activated_at) values (?,?,?,?)",
                result, target, state, state.equals("ACTIVE") ? NOW.minusSeconds(3600) : null);
        return result;
    }

    private Object[] parameters(Object[] args) {
        return Arrays.stream(args).map(value -> value instanceof Instant instant ? Timestamp.from(instant) : value).toArray();
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

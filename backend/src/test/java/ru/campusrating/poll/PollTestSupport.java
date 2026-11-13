package ru.campusrating.poll;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
import static org.awaitility.Awaitility.await;

abstract class PollTestSupport extends PostgresIntegrationTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String POLLS = "/api/v1/polls";
    static final Instant NOW = Instant.parse("2026-11-18T09:00:00Z");
    static final Instant ENDS = Instant.parse("2026-11-20T15:00:00Z");
    static final String TEACHER_NAME = "Петров Павел Петрович";
    static final String STUDENT_NAME = "Смирнова Анна Олеговна";
    static final String CLASSMATE_NAME = "Кузнецов Дмитрий Андреевич";
    static final List<String> POLL_TABLES = List.of("poll", "poll_group", "poll_question", "poll_option",
            "poll_participation", "poll_named_choice");

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired PasswordEncoder passwords;

    final List<Long> users = new ArrayList<>();
    final Map<String, Browser> sessions = new HashMap<>();
    String prefix;
    String passwordHash;
    long faculty;
    long otherFaculty;
    long group;
    long secondGroup;
    long otherGroup;
    long department;
    long teacher;
    long otherTeacher;
    long unapproved;
    long admin;
    long student;
    long classmate;
    long pending;
    long neighbour;
    long outsider;

    @BeforeEach
    void pollFixture() {
        clock.set(NOW);
        prefix = UUID.randomUUID().toString();
        passwordHash = passwords.encode("Password-42");
        faculty = id("insert into faculty(code,name) values (?,?) returning id", prefix, "Факультет опросов");
        otherFaculty = id("insert into faculty(code,name) values (?,?) returning id", prefix + "-other", "Другой факультет");
        group = group(faculty, "-21", "ИВТ-21");
        secondGroup = group(faculty, "-22", "ИВТ-22");
        otherGroup = group(otherFaculty, "-31", "ЭК-31");
        department = id("insert into department(code,name,faculty_id) values (?,?,?) returning id", prefix, "Кафедра", faculty);
        teacher = user("teacher", "TEACHER", TEACHER_NAME, department);
        otherTeacher = user("otherTeacher", "TEACHER", "Волков Максим Игоревич", department);
        unapproved = user("unapproved", "TEACHER", "Новиков Николай Николаевич", department);
        update("insert into teacher_approval(teacher_id,approved_at) values (?,?),(?,?)", teacher, NOW, otherTeacher, NOW);
        admin = user("admin", "ADMIN", "Администратор стенда", null);
        student = student("student", STUDENT_NAME, group, "ACTIVE");
        classmate = student("classmate", CLASSMATE_NAME, group, "ACTIVE");
        student("third", "Алексеев Артём Ильич", group, "ACTIVE");
        student("fourth", "Белова Вера Павловна", group, "ACTIVE");
        student("fifth", "Григорьев Глеб Олегович", group, "ACTIVE");
        student("sixth", "Дмитриева Дарья Сергеевна", group, "ACTIVE");
        pending = student("pending", "Орлов Никита Сергеевич", group, "PENDING");
        neighbour = student("neighbour", "Иванов Иван Иванович", secondGroup, "ACTIVE");
        outsider = student("outsider", "Сидоров Сидор Сидорович", otherGroup, "ACTIVE");
    }

    @AfterEach
    void cleanPollFixture() {
        drainEvents();
        truncatePollTables();
        update("delete from event_publication where event_type like 'ru.campusrating.poll.%'");
        for (long user : users) {
            update("delete from user_event where recipient_id=?", user);
            update("delete from group_membership where student_id=?", user);
            update("delete from teacher_grant where teacher_id=?", user);
            update("delete from teacher_approval where teacher_id=?", user);
        }
        for (long user : users) {
            update("delete from app_user where id=?", user);
        }
        update("delete from department where id=?", department);
        update("delete from study_group where id in (?,?,?)", group, secondGroup, otherGroup);
        update("delete from faculty where id in (?,?)", faculty, otherFaculty);
    }

    void truncatePollTables() {
        var existing = jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = current_schema() and (table_name = 'poll' or table_name like 'poll\\_%')
                """, String.class);
        if (!existing.isEmpty()) {
            update("truncate table " + String.join(", ", existing) + " cascade");
        }
    }

    void drainEvents() {
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject("""
                select count(*) from event_publication
                where completion_date is null and coalesce(status, '') <> 'FAILED'
                """, Integer.class) == 0);
    }

    Browser session(String name) {
        return sessions.computeIfAbsent(name, this::login);
    }

    Browser teacherSession() { return session("teacher"); }
    Browser studentSession() { return session("student"); }
    Browser classmateSession() { return session("classmate"); }

    static Map<String, Object> question(String text, String type, String... options) {
        var result = new LinkedHashMap<String, Object>();
        result.put("text", text);
        result.put("type", type);
        result.put("options", List.of(options));
        return result;
    }

    static Map<String, Object> pollBody(String title, Instant endsAt, boolean anonymous, List<Long> groups,
                                        List<Map<String, Object>> questions) {
        var result = new LinkedHashMap<String, Object>();
        result.put("title", title);
        result.put("description", "Выберите удобное время.");
        result.put("endsAt", endsAt.toString());
        result.put("groupIds", groups);
        result.put("anonymous", anonymous);
        result.put("questions", questions);
        return result;
    }

    static Map<String, Object> formatPoll(boolean anonymous, long... groups) {
        return pollBody("Формат зачёта", ENDS, anonymous, Arrays.stream(groups).boxed().toList(),
                List.of(question("Когда удобнее?", "SINGLE", "Утро", "Вечер")));
    }

    JsonNode create(Map<String, Object> body, Browser session, int status) {
        return request(post(POLLS).content(JSON.writeValueAsString(body)), session, status);
    }

    JsonNode createPoll(Map<String, Object> body) {
        return create(body, teacherSession(), 201);
    }

    JsonNode formatPoll(boolean anonymous) {
        return createPoll(formatPoll(anonymous, group));
    }

    JsonNode poll(long pollId, Browser session, int status) {
        return request(get(POLLS + "/" + pollId), session, status);
    }

    static Map<String, Object> answer(long questionId, long... optionIds) {
        var result = new LinkedHashMap<String, Object>();
        result.put("questionId", questionId);
        result.put("optionIds", Arrays.stream(optionIds).boxed().toList());
        return result;
    }

    @SafeVarargs
    final Response vote(long pollId, Browser session, Map<String, Object>... answers) {
        var body = Map.of("answers", List.of(answers));
        return exchange(post(POLLS + "/" + pollId + "/vote").content(JSON.writeValueAsString(body)), session);
    }

    @SafeVarargs
    final JsonNode vote(long pollId, Browser session, int status, Map<String, Object>... answers) {
        var result = vote(pollId, session, answers);
        assertThat(result.status()).withFailMessage(result.body()).isEqualTo(status);
        return JSON.readTree(result.body());
    }

    static long questionId(JsonNode poll, int question) {
        return poll.path("questions").get(question).path("id").asLong();
    }

    static long optionId(JsonNode poll, int question, int option) {
        return poll.path("questions").get(question).path("options").get(option).path("id").asLong();
    }

    static JsonNode optionResult(JsonNode results, long optionId) {
        for (var question : results.path("questions")) {
            for (var option : question.path("options")) {
                if (option.path("optionId").asLong() == optionId) {
                    return option;
                }
            }
        }
        throw new AssertionError("Нет результата варианта " + optionId + " в " + results);
    }

    static void assertVotes(JsonNode results, long optionId, int votes, int share) {
        var option = optionResult(results, optionId);
        assertThat(option.path("votes").asInt()).as("votes of %s", optionId).isEqualTo(votes);
        assertThat(option.path("share").asInt()).as("share of %s", optionId).isEqualTo(share);
    }

    static void problem(JsonNode result, String code) {
        assertThat(result.path("code").asText()).isEqualTo(code);
    }

    static String field(JsonNode result) {
        assertThat(result.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(result.path("fieldErrors")).withFailMessage(result.toString()).isNotEmpty();
        return result.path("fieldErrors").get(0).path("field").asText();
    }

    static List<Long> ids(JsonNode items) {
        var result = new ArrayList<Long>();
        items.forEach(item -> result.add(item.path("id").asLong()));
        return result;
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

    static Request delete(String path) {
        return new Request("DELETE", path, null);
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
        assertThat(result.status()).withFailMessage("%s %s -> %s %s", request.method(), request.path(), result.status(),
                result.body()).isEqualTo(status);
        return result.body() == null || result.body().isEmpty() ? JSON.createObjectNode() : JSON.readTree(result.body());
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

    Browser login(String name) {
        var browser = new Browser();
        var body = Map.of("email", prefix + name + "@example.test", "password", "Password-42");
        request(post("/api/v1/auth/login").content(JSON.writeValueAsString(body)), browser, 200);
        return browser;
    }

    long user(String name, String role, String fullName, Long departmentId) {
        long distinctive = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
        long result = id("""
                insert into app_user(id,email,role,full_name,password_hash,department_id) values (?,?,?,?,?,?) returning id
                """, distinctive, prefix + name + "@example.test", role, fullName, passwordHash, departmentId);
        users.add(result);
        return result;
    }

    long student(String login, String fullName, long target, String state) {
        long result = user(login, "STUDENT", fullName, null);
        update("insert into group_membership(student_id,group_id,state,activated_at) values (?,?,?,?)",
                result, target, state, state.equals("ACTIVE") ? NOW.minusSeconds(3600) : null);
        return result;
    }

    long group(long facultyId, String suffix, String name) {
        return id("insert into study_group(code,name,faculty_id,admission_year) values (?,?,?,2026) returning id",
                prefix + suffix, name, facultyId);
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

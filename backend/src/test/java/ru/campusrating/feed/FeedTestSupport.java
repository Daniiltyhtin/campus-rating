package ru.campusrating.feed;

import ru.campusrating.support.UserNameFixture;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.campusrating.ledger.Author;
import ru.campusrating.ledger.Currency;
import ru.campusrating.ledger.EntryKind;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.ledger.Posting;
import ru.campusrating.ledger.PostingResult;
import ru.campusrating.ledger.Source;
import ru.campusrating.ledger.SourceType;
import ru.campusrating.support.MutableClock;
import ru.campusrating.support.PostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

abstract class FeedTestSupport extends PostgresIntegrationTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String AWARDS = "/api/v1/feed/awards";
    static final long ACTIVITY_ID = 741L;
    static final String ACTIVITY_TITLE = "Снимок названия активности";
    static final String AUTHOR_NAME = "Снимок ФИО преподавателя";

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MutableClock clock;
    @Autowired
    PasswordEncoder passwords;
    @Autowired
    LedgerApi ledger;
    @Autowired
    PlatformTransactionManager transactions;

    final List<Long> users = new ArrayList<>();
    long faculty;
    long otherFaculty;
    long group;
    long emptyGroup;
    long otherGroup;
    long teacher;
    long student;
    long classmate;
    long twinFirst;
    long twinSecond;
    long zero;
    long pending;
    long outsider;
    String prefix;
    String passwordHash;

    @BeforeEach
    void fixture() {
        prefix = UUID.randomUUID().toString();
        passwordHash = passwords.encode("Password-42");
        Timestamp latestEntry = jdbc.queryForObject("select max(created_at) from ledger_entry", Timestamp.class);
        Instant latest = latestEntry == null ? Instant.parse("2026-11-12T09:00:00Z") : latestEntry.toInstant();
        clock.set(latest.plusSeconds(86400));
        faculty = id("insert into faculty(code,name) values (?,?) returning id", prefix, "Факультет ленты");
        otherFaculty = id("insert into faculty(code,name) values (?,?) returning id", prefix + "other", "Другой факультет");
        group = group(faculty, "Основная");
        emptyGroup = group(faculty, "Пустая");
        otherGroup = group(otherFaculty, "Чужая");
        long department = id("insert into department(code,name,faculty_id) values (?,?,?) returning id", prefix, "Кафедра", faculty);
        teacher = user("teacher", "TEACHER", department);
        user("unapproved", "TEACHER", department);
        user("admin", "ADMIN", null);
        update("insert into teacher_approval(teacher_id,approved_at) values (?,?)", teacher, clock.instant());
        student = student("student", "Яковлев Яков Яковлевич", group, "ACTIVE");
        classmate = student("classmate", "Антонов Антон Антонович", group, "ACTIVE");
        twinFirst = student("twinFirst", "Белов Борис Борисович", group, "ACTIVE");
        twinSecond = student("twinSecond", "Белов Борис Борисович", group, "ACTIVE");
        zero = student("zero", "Нулевой Николай Николаевич", group, "ACTIVE");
        pending = student("pending", "Ожидающий Олег Олегович", group, "PENDING");
        outsider = student("outsider", "Чужой Человек", otherGroup, "ACTIVE");
        student("emptyPending", "Ожидающий подтверждения", emptyGroup, "PENDING");
    }

    @AfterEach
    void cleanFixture() {
        for (long user : users) {
            update("delete from ledger_entry where student_id=?", user);
            update("delete from ledger_balance where student_id=?", user);
            update("delete from group_membership where student_id=?", user);
            update("delete from teacher_grant where teacher_id=?", user);
            update("delete from teacher_approval where teacher_id=?", user);
        }
        for (long user : users) {
            update("delete from app_user where id=?", user);
        }
        update("delete from department where code=?", prefix);
        update("delete from study_group where id in (?,?,?)", group, emptyGroup, otherGroup);
        update("delete from faculty where id in (?,?)", faculty, otherFaculty);
    }

    long award(long target, Currency currency, int amount) {
        return post(new Posting(target, currency, EntryKind.ACTIVITY_AWARD, amount,
                new Source(SourceType.ACTIVITY, ACTIVITY_ID, ACTIVITY_TITLE),
                new Author(teacher, AUTHOR_NAME), UUID.randomUUID().toString()));
    }

    long ar(long target, EntryKind kind, int amount) {
        SourceType type = switch (kind) {
            case INITIAL -> SourceType.REGISTRATION;
            case PENALTY -> SourceType.CHECKPOINT;
            case SEMESTER_ACCRUAL -> SourceType.SEMESTER;
            case ACTIVITY_AWARD -> throw new IllegalArgumentException();
        };
        return post(new Posting(target, Currency.AR, kind, amount,
                new Source(type, 742L, "Системное начисление"), null, UUID.randomUUID().toString()));
    }

    long post(Posting posting) {
        var results = new TransactionTemplate(transactions).execute(status -> ledger.post(List.of(posting)));
        assertThat(results).singleElement().isInstanceOf(PostingResult.Posted.class);
        return ((PostingResult.Posted) results.getFirst()).entryId();
    }

    void problem(JsonNode result, int status, String code) {
        assertThat(result.path("status").asInt()).isEqualTo(status);
        assertThat(result.path("code").asText()).isEqualTo(code);
    }

    void validation(JsonNode result, String field) {
        problem(result, 400, "VALIDATION_FAILED");
        assertThat(result.path("fieldErrors")).anySatisfy(error ->
                assertThat(error.path("field").asText()).isEqualTo(field));
    }

    void userRef(JsonNode result, long id, String name) {
        assertThat(result.path("id").asLong()).isEqualTo(id);
        assertThat(result.path("fullName").asText()).isEqualTo(name);
    }

    List<Long> ids(JsonNode items, String field) {
        var ids = new ArrayList<Long>();
        items.forEach(item -> ids.add(item.path(field).asLong()));
        return ids;
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
        long result = id("insert into app_user(email,role,last_name,first_name,middle_name,password_hash,department_id) values (?,?,?,?,?,?,?) returning id",
                prefix + name + "@example.test",
                role,
                UserNameFixture.last(name),
                UserNameFixture.first(name),
                UserNameFixture.middle(name),
                passwordHash,
                department);
        users.add(result);
        return result;
    }

    long student(String login, String name, long target, String state) {
        long result = user(login, "STUDENT", null);
        update("update app_user set last_name=?,first_name=?,middle_name=? where id=?",
                UserNameFixture.last(name),
                UserNameFixture.first(name),
                UserNameFixture.middle(name),
                result);
        update("insert into group_membership(student_id,group_id,state,activated_at) values (?,?,?,?)",
                result, target, state, state.equals("ACTIVE") ? clock.instant() : null);
        return result;
    }

    long group(long facultyId, String name) {
        return id("insert into study_group(code,name,faculty_id,admission_year) values (?,?,?,2026) returning id",
                prefix + name, name, facultyId);
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
}

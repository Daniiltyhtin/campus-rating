package ru.campusrating.checkpoint;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import ru.campusrating.checkpoint.internal.PenaltyJob;
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
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"campus.mail.email-verification=off", "campus.penalty.scheduler.enabled=false"})
abstract class SubmissionWithdrawalSupport extends PostgresIntegrationTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired PasswordEncoder passwords;
    @Autowired LedgerApi ledger;
    @Autowired PenaltyJob job;
    @Autowired PlatformTransactionManager transactions;
    @Value("${campus.storage.dir}") Path storage;
    String prefix;
    long faculty, department, group, teacher, student, other, admin;
    long checkpoint, assignment;
    Instant deadline;
    Browser owner, author, stranger, administrator;

    @BeforeEach
    void fixture() {
        prefix = UUID.randomUUID().toString();
        deadline = clock.instant().plusSeconds(600);
        faculty = id("insert into faculty(code,name) values (?,'Факультет') returning id", prefix);
        department = id("insert into department(code,name,faculty_id) values (?,'Кафедра',?) returning id", prefix, faculty);
        group = id("insert into study_group(code,name,faculty_id,admission_year) values (?,'ИВТ',?,2026) returning id", prefix, faculty);
        teacher = person("teacher", "TEACHER", department);
        student = person("student", "STUDENT", null);
        other = person("other", "STUDENT", null);
        admin = person("admin", "ADMIN", null);
        jdbc.update("insert into teacher_approval(teacher_id,approved_at) values (?,?)", teacher, Timestamp.from(clock.instant()));
        for (long user : List.of(student, other)) {
            jdbc.update("insert into group_membership(student_id,group_id,state,activated_at) values (?,?,'ACTIVE',?)", user, group, Timestamp.from(clock.instant().minusSeconds(60)));
        }
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> ledger.post(List.of(
                new Posting(student, Currency.AR, EntryKind.INITIAL, 1000,
                        new Source(SourceType.REGISTRATION, student, "Фикстура"), null, "initial:" + student))));
        owner = login("student");
        author = login("teacher");
        stranger = login("other");
        administrator = login("admin");
        checkpoint = id("""
                insert into checkpoint(author_id,title,description,deadline,cost,penalty_step,penalty_interval_seconds,created_at,updated_at)
                values (?,'Работа','Описание',?,60,20,60,?,?) returning id
                """, teacher, Timestamp.from(deadline), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
        jdbc.update("insert into checkpoint_group(checkpoint_id,group_id) values (?,?)", checkpoint, group);
        assignment = id("""
                insert into assignment(checkpoint_id,student_id,group_id,student_full_name,state,created_at)
                values (?,?,?,'Студент','OPEN',?) returning id
                """, checkpoint, student, group, Timestamp.from(clock.instant()));
    }

    @AfterEach
    void cleanup() throws Exception {
        var points = jdbc.queryForList("select id from checkpoint where author_id=?", Long.class, teacher);
        for (long point : points) {
            String event = JSON.writeValueAsString(Map.of("checkpointId", point));
            await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                    "select count(*) from event_publication where event_type=? and serialized_event::jsonb=?::jsonb and completion_date is null",
                    Integer.class, CheckpointCreated.class.getName(), event) == 0);
            jdbc.update("delete from event_publication where event_type=? and serialized_event::jsonb=?::jsonb", CheckpointCreated.class.getName(), event);
        }
        var keys = jdbc.queryForList("select file_key from submission where file_key is not null and assignment_id in (select id from assignment where checkpoint_id in (select id from checkpoint where author_id=?))", String.class, teacher);
        for (String key : keys) Files.deleteIfExists(storage.resolve(key));
        jdbc.update("delete from submission where assignment_id in (select id from assignment where checkpoint_id in (select id from checkpoint where author_id=?))", teacher);
        jdbc.update("delete from assignment where checkpoint_id in (select id from checkpoint where author_id=?)", teacher);
        jdbc.update("delete from checkpoint_group where checkpoint_id in (select id from checkpoint where author_id=?)", teacher);
        jdbc.update("delete from checkpoint where author_id=?", teacher);
        jdbc.update("delete from ledger_entry where student_id in (?,?)", student, other);
        jdbc.update("delete from ledger_balance where student_id in (?,?)", student, other);
        jdbc.update("delete from group_membership where student_id in (?,?)", student, other);
        jdbc.update("delete from teacher_approval where teacher_id=?", teacher);
        jdbc.update("delete from app_user where id in (?,?,?,?)", student, other, teacher, admin);
        jdbc.update("delete from study_group where id=?", group);
        jdbc.update("delete from department where id=?", department);
        jdbc.update("delete from faculty where id=?", faculty);
    }

    JsonNode create() {
        return author.post("/api/v1/checkpoints", Map.of("title", "Новая точка", "description", "Описание",
                "deadline", deadline.toString(), "cost", 60, "groupIds", List.of(group))).expect(201);
    }

    JsonNode read() { return owner.get(path()).expect(200); }

    JsonNode submit() {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("link", "https://example.org/" + UUID.randomUUID());
        return owner.multipart(path() + "/submission", parts).expect(200);
    }

    JsonNode submitFile(byte[] bytes) {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return "report.pdf"; }
        });
        return owner.multipart(path() + "/submission", parts).expect(200);
    }

    Reply withdraw() { return owner.post(path() + "/submission/withdrawal", null); }

    Reply accept() {
        return author.post("/api/v1/checkpoints/" + checkpoint + "/assignments/accept", Map.of("assignmentIds", List.of(assignment)));
    }

    JsonNode row() {
        return author.get("/api/v1/checkpoints/" + checkpoint + "/assignments").expect(200).path("items")
                .valueStream().filter(n -> n.path("assignmentId").asLong() == assignment).findFirst().orElseThrow();
    }

    String path() { return "/api/v1/assignments/" + assignment; }

    void problem(Reply reply, int status, String code, String detail) {
        var body = reply.expect(status);
        assertThat(body.path("code").asText()).isEqualTo(code);
        if (detail != null) assertThat(body.path("detail").asText()).isEqualTo(detail);
    }

    long id(String sql, Object... parameters) { return jdbc.queryForObject(sql, Long.class, parameters); }

    private long person(String name, String role, Long departmentId) {
        return id("insert into app_user(email,role,full_name,password_hash,department_id) values (?,?,?,?,?) returning id",
                prefix + name + "@example.test", role, name, passwords.encode("Password-42"), departmentId);
    }

    private Browser login(String name) {
        var browser = new Browser();
        browser.post("/api/v1/auth/login", Map.of("email", prefix + name + "@example.test", "password", "Password-42")).expect(200);
        return browser;
    }

    record Reply(int status, JsonNode body) {
        JsonNode expect(int expected) {
            assertThat(status).as(body.toString()).isEqualTo(expected);
            return body;
        }
    }

    final class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final RestTestClient client;

        Browser() {
            var http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
            client = RestTestClient.bindToServer(new JdkClientHttpRequestFactory(http))
                    .baseUrl("http://127.0.0.1:" + port).build();
        }

        byte[] download(String path) {
            return client.get().uri(path).exchange().expectStatus().isOk().returnResult(byte[].class).getResponseBody();
        }

        Reply get(String path) { return request("GET", path, null, MediaType.APPLICATION_JSON, false); }
        Reply post(String path, Map<String, ?> body) {
            return request("POST", path, body == null ? null : JSON.writeValueAsString(body), MediaType.APPLICATION_JSON, true);
        }
        Reply multipart(String path, LinkedMultiValueMap<String, Object> body) {
            return request("POST", path, body, MediaType.MULTIPART_FORM_DATA, true);
        }
        Reply withoutCsrf(String path) { return request("POST", path, null, MediaType.APPLICATION_JSON, false); }

        private Reply request(String method, String path, Object body, MediaType type, boolean csrf) {
            if (csrf) client.get().uri("/api/v1/auth/csrf").exchange().expectStatus().isNoContent();
            var call = client.method(HttpMethod.valueOf(method)).uri(path).contentType(type);
            if (csrf) {
                var token = cookies.getCookieStore().getCookies().stream()
                        .filter(cookie -> cookie.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow();
                call.header("X-XSRF-TOKEN", URLDecoder.decode(token.getValue(), StandardCharsets.UTF_8));
            }
            if (body != null) call.body(body);
            var response = call.exchange().returnResult(String.class);
            String text = response.getResponseBody();
            return new Reply(response.getStatus().value(), text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text));
        }
    }
}

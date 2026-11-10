package ru.campusrating.identity;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.support.MutableClock;
import ru.campusrating.support.PostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

abstract class IdentityAcceptanceSupport extends PostgresIntegrationTest {
    protected static final String PASSWORD = "Synthetic-password-42";
    protected static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MutableClock clock;
    @Autowired protected LedgerApi ledger;
    @LocalServerPort private int port;
    protected String prefix;
    protected long faculty;
    protected long otherFaculty;
    protected long group;
    protected long otherGroup;
    protected long department;
    private final List<Long> groups = new ArrayList<>();

    @BeforeEach
    void directoryFixture() {
        prefix = UUID.randomUUID().toString();
        faculty = faculty("A");
        otherFaculty = faculty("B");
        group = group(faculty, 2026, "A");
        otherGroup = group(otherFaculty, 2026, "B");
        department = jdbc.queryForObject("insert into department(code,name,faculty_id) values (?,?,?) returning id",
                Long.class, prefix + "-department", "Кафедра теста", faculty);
    }

    @AfterEach
    void cleanFixture() {
        var users = jdbc.queryForList("select id from app_user where email like ?", Long.class, prefix + "%");
        for (long id : users) {
            jdbc.update("delete from ledger_entry where student_id = ?", id);
            jdbc.update("delete from ledger_balance where student_id = ?", id);
            jdbc.update("delete from group_membership where student_id = ?", id);
            if (tableExists("teacher_grant")) jdbc.update("delete from teacher_grant where teacher_id = ?", id);
            if (tableExists("teacher_approval")) jdbc.update("delete from teacher_approval where teacher_id = ?", id);
        }
        if (tableExists("teacher_allowlist")) jdbc.update("delete from teacher_allowlist where email like ?", prefix + "%");
        for (long id : users) jdbc.update("delete from app_user where id = ?", id);
        jdbc.update("delete from department where id = ?", department);
        for (long id : groups) jdbc.update("delete from study_group where id = ?", id);
        jdbc.update("delete from faculty where id in (?,?)", faculty, otherFaculty);
        groups.clear();
    }

    private boolean tableExists(String table) {
        return jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, "public." + table);
    }

    private long faculty(String suffix) {
        return jdbc.queryForObject("insert into faculty(code,name) values (?,?) returning id", Long.class,
                prefix + suffix, "Факультет " + suffix);
    }

    protected long group(long facultyId, int admissionYear, String suffix) {
        long id = jdbc.queryForObject("insert into study_group(code,name,faculty_id,admission_year) values (?,?,?,?) returning id",
                Long.class, prefix + suffix, "Группа " + suffix, facultyId, admissionYear);
        groups.add(id);
        return id;
    }

    protected String email(String suffix) { return prefix + "-" + suffix + "@example.test"; }

    protected Map<String, Object> studentBody(String email, long facultyId, long groupId) {
        return Map.of("fullName", "Студент Приёмочный", "email", email, "password", PASSWORD,
                "facultyId", facultyId, "groupId", groupId);
    }

    protected long registerStudent(Browser browser, String suffix) {
        var response = browser.post("/api/v1/auth/register/student", studentBody(email(suffix), faculty, group));
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().path("nextStep").asText()).isEqualTo("LOGIN");
        return response.body().path("userId").asLong();
    }

    protected long registerTeacher(Browser browser, String suffix) {
        var response = browser.post("/api/v1/auth/register/teacher", Map.of("fullName", "Преподаватель Приёмочный",
                "email", email(suffix), "password", PASSWORD, "departmentId", department));
        assertThat(response.status()).isEqualTo(201);
        return response.body().path("userId").asLong();
    }

    protected void login(Browser browser, String email) {
        assertThat(browser.post("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD)).status()).isEqualTo(200);
    }

    protected void assertProblem(Reply reply, int status, String code) {
        assertThat(reply.status()).isEqualTo(status);
        assertThat(reply.result().getResponseHeaders().getContentType()).isNotNull();
        assertThat(reply.result().getResponseHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(reply.body().path("status").asInt()).isEqualTo(status);
        assertThat(reply.body().path("code").asText()).isEqualTo(code);
        assertThat(reply.body().has("properties")).isFalse();
    }

    protected Browser browser() { return new Browser(port); }

    protected record Reply(EntityExchangeResult<String> result, JsonNode body) {
        int status() { return result.getStatus().value(); }
    }

    protected static final class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final RestTestClient client;

        Browser(int port) {
            var http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
            client = RestTestClient.bindToServer(new JdkClientHttpRequestFactory(http))
                    .baseUrl("http://127.0.0.1:" + port).build();
        }

        Reply get(String path) { return request("GET", path, null, false); }

        Reply getWithCookie(String path, String rawCookie) {
            return reply(client.get().uri(path).header("Cookie", rawCookie).exchange().returnResult(String.class));
        }

        Reply post(String path, Map<String, ?> body) {
            assertThat(get("/api/v1/auth/csrf").status()).isEqualTo(204);
            return request("POST", path, body, true);
        }

        Reply postWithoutCsrf(String path, Map<String, ?> body) { return request("POST", path, body, false); }

        List<HttpCookie> cookies() { return cookies.getCookieStore().getCookies(); }

        private Reply request(String method, String path, Map<String, ?> body, boolean csrf) {
            var request = client.method(HttpMethod.valueOf(method)).uri(path).contentType(MediaType.APPLICATION_JSON);
            if (csrf) {
                var token = cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                        .findFirst().orElseThrow(() -> new AssertionError("CSRF cookie is missing"));
                request.header("X-XSRF-TOKEN", URLDecoder.decode(token.getValue(), StandardCharsets.UTF_8));
            }
            if (body != null) request.body(JSON.writeValueAsString(body));
            return reply(request.exchange().returnResult(String.class));
        }

        private Reply reply(EntityExchangeResult<String> result) {
            String text = result.getResponseBody();
            return new Reply(result, text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text));
        }
    }
}

package ru.campusrating.support;

import ru.campusrating.support.UserNameFixture;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class AdminHttpTestSupport extends PostgresIntegrationTest {
    protected static final String PASSWORD = "Synthetic-password-42";
    protected static final long ABSENT = Long.MAX_VALUE;
    protected static final String TEACHERS = "/api/v1/admin/teachers";
    protected static final String DIRECTORY = "/api/v1/admin/directory/";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MutableClock clock;
    @Autowired private PasswordEncoder passwords;
    @LocalServerPort private int port;
    protected String prefix;
    protected long faculty;
    protected long otherFaculty;
    protected long thirdFaculty;
    protected long department;
    protected long otherDepartment;
    protected long group;
    protected long sameNameGroup;
    protected long otherGroup;
    protected long olderGroup;
    protected long thirdGroup;
    protected Browser admin;

    @BeforeEach
    void fixture() {
        prefix = UUID.randomUUID().toString();
        clock.set(Instant.parse("2026-11-12T09:00:00Z"));
        faculty = faculty("f1", "Faculty");
        otherFaculty = faculty("f2", "Other faculty");
        thirdFaculty = faculty("f3", "Third faculty");
        department = department(faculty, "d1");
        otherDepartment = department(otherFaculty, "d2");
        group = group(faculty, "g1", "Same", 2026);
        sameNameGroup = group(faculty, "g2", "Same", 2026);
        otherGroup = group(otherFaculty, "g3", "Alpha", 2026);
        olderGroup = group(otherFaculty, "g4", "Zulu", 2025);
        thirdGroup = group(thirdFaculty, "g5", "Beta", 2026);
        admin = administrator("admin", true);
    }

    @AfterEach
    void cleanup() {
        var users = jdbc.queryForList("select id from app_user where email like ?", Long.class, prefix + "%");
        for (long user : users) {
            jdbc.update("delete from checkpoint_group where checkpoint_id in (select id from checkpoint where author_id=?)", user);
            jdbc.update("delete from checkpoint where author_id=?", user);
            jdbc.update("delete from activity_group where activity_id in (select id from activity where author_id=?)", user);
            jdbc.update("delete from activity where author_id=?", user);
            jdbc.update("delete from ledger_entry where student_id=?", user);
            jdbc.update("delete from ledger_balance where student_id=?", user);
            jdbc.update("delete from group_membership where student_id=?", user);
            jdbc.update("delete from teacher_grant where teacher_id=?", user);
        }
        jdbc.update("delete from teacher_approval where teacher_id in (select id from app_user where email like ?) or approved_by in (select id from app_user where email like ?)", prefix + "%", prefix + "%");
        jdbc.update("delete from teacher_allowlist where email like ?", prefix + "%");
        jdbc.update("delete from app_user where email like ?", prefix + "%");
        jdbc.update("delete from department where code like ?", prefix + "%");
        jdbc.update("delete from study_group where code like ?", prefix + "%");
        jdbc.update("delete from faculty where code like ?", prefix + "%");
    }

    protected long faculty(String suffix, String name) {
        return jdbc.queryForObject("insert into faculty(code,name) values (?,?) returning id", Long.class, prefix + suffix, name);
    }

    protected long department(long facultyId, String suffix) {
        return jdbc.queryForObject("insert into department(code,name,faculty_id) values (?,?,?) returning id", Long.class,
                prefix + suffix, suffix, facultyId);
    }

    protected long group(long facultyId, String suffix, String name, int year) {
        return jdbc.queryForObject("insert into study_group(code,name,faculty_id,admission_year) values (?,?,?,?) returning id",
                Long.class, prefix + suffix, name, facultyId, year);
    }

    protected String email(String suffix) { return prefix + suffix + "@example.test"; }
    protected String teacherPath(long id) { return TEACHERS + "/" + id; }
    protected Browser browser() { return new Browser(); }

    protected Browser administrator(String suffix, boolean changePassword) {
        jdbc.update("insert into app_user(email,role,last_name,first_name,middle_name,password_hash,must_change_password) values (?,'ADMIN',?,?,?,?,true)",
                email(suffix),
                UserNameFixture.last(suffix),
                UserNameFixture.first(suffix),
                UserNameFixture.middle(suffix),
                passwords.encode(PASSWORD));
        var session = login(suffix);
        if (changePassword) request(session, "POST", "/api/v1/auth/password",
                Map.of("currentPassword", PASSWORD, "newPassword", "Changed-password-43"), 204);
        return session;
    }

    protected Browser login(String suffix) {
        var session = browser();
        request(session, "POST", "/api/v1/auth/login", Map.of("email", email(suffix), "password", PASSWORD), 200);
        return session;
    }

    protected long teacher(String suffix, String name) {
        return request(browser(), "POST", "/api/v1/auth/register/teacher", Map.of("email", email(suffix), "password", PASSWORD, "lastName", UserNameFixture.last(name), "firstName", UserNameFixture.first(name), "middleName", UserNameFixture.middleOrEmpty(name), "departmentId", department), 201).path("userId").asLong();
    }

    protected long student(String suffix, long facultyId, long groupId) {
        return request(browser(), "POST", "/api/v1/auth/register/student", Map.of("email", email(suffix), "password", PASSWORD, "lastName", UserNameFixture.last(suffix), "firstName", UserNameFixture.first(suffix), "middleName", UserNameFixture.middleOrEmpty(suffix), "facultyId", facultyId, "groupId", groupId), 201)
                .path("userId").asLong();
    }

    protected JsonNode approve(long teacher, long targetDepartment) {
        return request(admin, "POST", teacherPath(teacher) + "/approval", Map.of("departmentId", targetDepartment), 200);
    }

    protected JsonNode grant(long teacher, Map<String, ?> body, int status) {
        return request(admin, "POST", teacherPath(teacher) + "/grants", body, status);
    }

    protected JsonNode get(Browser session, String path) { return request(session, "GET", path, null, 200); }
    protected JsonNode get(String path) { return get(admin, path); }
    protected List<JsonNode> items(JsonNode body) {
        assertThat(body.path("items").isArray()).isTrue();
        var result = new ArrayList<JsonNode>();
        body.path("items").forEach(result::add);
        return result;
    }
    protected List<Long> ids(JsonNode body) { return items(body).stream().map(n -> n.path("id").asLong()).toList(); }
    protected List<Long> scope(long teacher) { return ids(get(teacherPath(teacher) + "/accessible-groups")); }
    protected void sorted(JsonNode body, String nameField) {
        assertThat(items(body)).isSortedAccordingTo(Comparator.comparing((JsonNode n) -> n.path(nameField).asText())
                .thenComparingLong(n -> n.path("id").asLong()));
    }
    protected void teacherScope(Browser session, List<Long> expected) {
        var body = get(session, "/api/v1/me/accessible-groups");
        assertThat(ids(body)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(items(body)).isSortedAccordingTo(Comparator.comparing((JsonNode n) -> n.path("faculty").path("name").asText())
                .thenComparing(n -> n.path("name").asText()).thenComparingLong(n -> n.path("id").asLong()));
    }
    protected Map<String, Object> checkpoint(long target) {
        return Map.of("title", prefix + " checkpoint", "description", "", "groupIds", List.of(target),
                "deadline", clock.instant().plusSeconds(86400).toString(), "cost", 60);
    }

    protected JsonNode problem(Browser session, String method, String path, Map<String, ?> body, int status, String code) {
        var result = request(session, method, path, body, status);
        assertThat(result.path("status").asInt()).isEqualTo(status);
        assertThat(result.path("code").asText()).isEqualTo(code);
        return result;
    }
    protected void invalid(String method, String path, Map<String, ?> body, String field) {
        var result = problem(admin, method, path, body, 400, "VALIDATION_FAILED");
        assertThat(result.path("fieldErrors").isArray()).isTrue();
        assertThat(result.path("fieldErrors").valueStream().map(n -> n.path("field").asText()).toList()).contains(field);
    }

    protected JsonNode request(Browser session, String method, String path, Map<String, ?> body, int status) {
        boolean mutation = !method.equals("GET");
        if (mutation) session.client.get().uri("/api/v1/auth/csrf").exchange().expectStatus().isNoContent();
        var call = session.client.method(HttpMethod.valueOf(method)).uri(path).contentType(MediaType.APPLICATION_JSON);
        if (mutation) {
            var token = session.cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow();
            call.header("X-XSRF-TOKEN", URLDecoder.decode(token.getValue(), StandardCharsets.UTF_8));
        }
        if (body != null) call.body(JSON.writeValueAsString(body));
        var result = call.exchange().returnResult(String.class);
        assertThat(result.getStatus().value()).withFailMessage("%s %s: %s", method, path, result.getResponseBody()).isEqualTo(status);
        String text = result.getResponseBody();
        if (status == 204) assertThat(text).isNullOrEmpty();
        if (status >= 400) assertThat(result.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        return text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
    }

    protected final class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final RestTestClient client = RestTestClient.bindToServer(new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().cookieHandler(cookies).build())).baseUrl("http://127.0.0.1:" + port).build();
    }
}

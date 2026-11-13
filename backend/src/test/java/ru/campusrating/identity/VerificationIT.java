package ru.campusrating.identity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.campusrating.CampusRatingApplication;
import ru.campusrating.support.U13MailTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "campus.mail.email-verification=required")
class VerificationIT extends U13MailTestSupport {
    private static final String CONFIRM = "/email/confirm/";

    @BeforeEach
    void verifyFixtureAdministrator() {
        jdbc.update("update app_user set email_verified=true where email=?", email("admin"));
        assertThat(get("/api/v1/auth/config").path("emailVerification").asText()).isEqualTo("REQUIRED");
    }

    @Test
    void r31PasswordIsCheckedBeforeEmailConfirmationAndNoSessionIsCreated() {
        student("unverified", faculty, group);
        var wrong = problem(browser(), "POST", "/api/v1/auth/login", Map.of("email", email("unverified"), "password", "Wrong-password-17"), 401, "INVALID_CREDENTIALS");
        assertThat(wrong.toString()).doesNotContain("EMAIL_NOT_VERIFIED");
        var session = browser();
        var blocked = problem(session, "POST", "/api/v1/auth/login", Map.of("email", email("unverified"), "password", PASSWORD), 403, "EMAIL_NOT_VERIFIED");
        assertThat(blocked.path("detail").asText()).isNotBlank();
        problem(session, "GET", "/api/v1/auth/me", null, 401, "UNAUTHENTICATED");
        problem(browser(), "POST", "/api/v1/auth/login", Map.of("email", email("absent"), "password", PASSWORD), 401, "INVALID_CREDENTIALS");
    }

    @Test
    void r31ExpiredConfirmationLinkIsRejectedAndEmailStaysUnverified() {
        student("expired", faculty, group);
        String token = token(email("expired"), CONFIRM);
        clock.advance(Duration.ofHours(25));
        problem(browser(), "POST", "/api/v1/auth/email/confirmation", Map.of("token", token), 409, "INVALID_STATE");
        assertThat(verified("expired")).isFalse();
        problem(browser(), "POST", "/api/v1/auth/login", Map.of("email", email("expired"), "password", PASSWORD), 403, "EMAIL_NOT_VERIFIED");
    }

    @Test
    void r31LinkIsValidJustBeforeTwentyFourHoursAndUsedLinkIsRejected() {
        student("boundary", faculty, group);
        String token = token(email("boundary"), CONFIRM);
        clock.advance(Duration.ofHours(24).minusSeconds(1));
        request(browser(), "POST", "/api/v1/auth/email/confirmation", Map.of("token", token), 204);
        assertThat(verified("boundary")).isTrue();
        problem(browser(), "POST", "/api/v1/auth/email/confirmation", Map.of("token", token), 409, "INVALID_STATE");
        login("boundary");
        student("unused", faculty, group);
        String first = token(email("unused"), CONFIRM);
        clock.advance(Duration.ofHours(24));
        problem(browser(), "POST", "/api/v1/auth/email/confirmation", Map.of("token", first), 409, "INVALID_STATE");
        assertThat(verified("unused")).isFalse();
    }

    @Test
    void r31UsedLinkDoesNotVerifyAnotherAccount() {
        student("first", faculty, group);
        student("second", faculty, group);
        String token = token(email("first"), CONFIRM);
        request(browser(), "POST", "/api/v1/auth/email/confirmation", Map.of("token", token), 204);
        problem(browser(), "POST", "/api/v1/auth/email/confirmation", Map.of("token", token), 409, "INVALID_STATE");
        assertThat(verified("first")).isTrue();
        assertThat(verified("second")).isFalse();
    }

    @Test
    void r2r31AllowlistedTeacherIsApprovedWithListDepartmentOnlyAfterConfirmation() {
        jdbc.update("insert into teacher_allowlist(email,department_id) values (?,?)", email("listed"), otherDepartment);
        long id = teacher("listed", "Listed teacher");
        problem(browser(), "POST", "/api/v1/auth/login", Map.of("email", email("listed"), "password", PASSWORD), 403, "EMAIL_NOT_VERIFIED");
        assertThat(jdbc.queryForObject("select count(*) from teacher_approval where teacher_id=?", Integer.class, id)).isZero();
        request(browser(), "POST", "/api/v1/auth/email/confirmation", Map.of("token", token(email("listed"), CONFIRM)), 204);
        var session = login("listed");
        var me = get(session, "/api/v1/auth/me");
        assertThat(me.path("teacherApproved").asBoolean()).isTrue();
        assertThat(me.at("/department/id").asLong()).isEqualTo(otherDepartment);
        assertThat(ids(get(session, "/api/v1/me/accessible-groups"))).contains(otherGroup).doesNotContain(group);
    }

    @Test
    void r2r31TeacherOutsideAllowlistWaitsForAdministratorAfterConfirmation() {
        teacher("outside", "Outside teacher");
        problem(browser(), "POST", "/api/v1/auth/login", Map.of("email", email("outside"), "password", PASSWORD), 403, "EMAIL_NOT_VERIFIED");
        request(browser(), "POST", "/api/v1/auth/email/confirmation", Map.of("token", token(email("outside"), CONFIRM)), 204);
        var session = login("outside");
        var me = get(session, "/api/v1/auth/me");
        assertThat(me.path("teacherApproved").asBoolean(true)).isFalse();
        assertThat(me.at("/department/id").asLong()).isEqualTo(department);
        assertThat(items(get(session, "/api/v1/me/accessible-groups"))).isEmpty();
    }

    @Test
    void r48OffModeSendsNoMailAndLetsStudentAndTeacherInImmediately() {
        try (var database = new PostgreSQLContainer("postgres:18.6-alpine")) {
            database.start();
            var mail = new CapturingSender();
            try (var off = start(database, "off", mail)) {
                var data = new Fixture(off);
                var browser = IdentityLifecycleSupport.browser(off);
                assertThat(browser.get("/api/v1/auth/config").body().path("emailVerification").asText()).isEqualTo("OFF");
                var student = browser.post("/api/v1/auth/register/student", Map.of("fullName", "Студент Офф", "email", data.email("student"),
                        "password", PASSWORD, "facultyId", data.faculty, "groupId", data.group));
                assertThat(student.status()).isEqualTo(201);
                assertThat(student.body().path("nextStep").asText()).isEqualTo("LOGIN");
                var teacher = browser.post("/api/v1/auth/register/teacher", Map.of("fullName", "Преподаватель Офф", "email", data.email("teacher"),
                        "password", PASSWORD, "departmentId", data.department));
                assertThat(teacher.status()).isEqualTo(201);
                assertThat(teacher.body().path("nextStep").asText()).isEqualTo("LOGIN");
                for (String who : List.of("student", "teacher")) {
                    var session = IdentityLifecycleSupport.browser(off);
                    assertThat(session.post("/api/v1/auth/login", Map.of("email", data.email(who), "password", PASSWORD)).status()).isEqualTo(200);
                    assertThat(session.get("/api/v1/auth/me").status()).isEqualTo(200);
                }
                var studentSession = IdentityLifecycleSupport.browser(off);
                studentSession.post("/api/v1/auth/login", Map.of("email", data.email("student"), "password", PASSWORD));
                assertThat(studentSession.get("/api/v1/auth/me").body().path("membershipState").asText()).isEqualTo("PENDING");
                assertThat(mail.messages).isEmpty();
            }
        }
    }

    @Test
    void r31SwitchFromOffToRequiredBlocksExistingUnverifiedUserUntilConfirmation() {
        try (var database = new PostgreSQLContainer("postgres:18.6-alpine")) {
            database.start();
            Fixture data;
            try (var off = start(database, "off", new CapturingSender())) {
                data = new Fixture(off);
                var browser = IdentityLifecycleSupport.browser(off);
                assertThat(browser.post("/api/v1/auth/register/student", Map.of("fullName", "Студент До Переключения", "email", data.email("legacy"),
                        "password", PASSWORD, "facultyId", data.faculty, "groupId", data.group)).status()).isEqualTo(201);
                var session = IdentityLifecycleSupport.browser(off);
                assertThat(session.post("/api/v1/auth/login", Map.of("email", data.email("legacy"), "password", PASSWORD)).status()).isEqualTo(200);
            }
            var mail = new CapturingSender();
            try (var required = start(database, "required", mail)) {
                var blocked = IdentityLifecycleSupport.browser(required).post("/api/v1/auth/login", Map.of("email", data.email("legacy"), "password", PASSWORD));
                assertThat(blocked.status()).isEqualTo(403);
                assertThat(blocked.body().path("code").asText()).isEqualTo("EMAIL_NOT_VERIFIED");
                assertThat(IdentityLifecycleSupport.browser(required).post("/api/v1/auth/email/verification", Map.of("email", data.email("legacy"))).status()).isEqualTo(204);
                String token = mail.token(data.email("legacy"));
                assertThat(IdentityLifecycleSupport.browser(required).post("/api/v1/auth/email/confirmation", Map.of("token", token)).status()).isEqualTo(204);
                var session = IdentityLifecycleSupport.browser(required);
                assertThat(session.post("/api/v1/auth/login", Map.of("email", data.email("legacy"), "password", PASSWORD)).status()).isEqualTo(200);
                assertThat(session.get("/api/v1/auth/me").status()).isEqualTo(200);
            }
        }
    }

    private boolean verified(String suffix) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select email_verified from app_user where email=?", Boolean.class, email(suffix)));
    }

    private static ConfigurableApplicationContext start(PostgreSQLContainer database, String mode, CapturingSender mail) {
        var properties = new HashMap<String, Object>();
        properties.put("server.port", 0);
        properties.put("spring.datasource.url", database.getJdbcUrl());
        properties.put("spring.datasource.username", database.getUsername());
        properties.put("spring.datasource.password", database.getPassword());
        properties.put("EMAIL_VERIFICATION", mode);
        properties.put("campus.mail.email-verification", mode);
        properties.put("spring.mail.host", "smtp.example.test");
        properties.put("campus.public-url", "https://campus.example.test");
        properties.put("campus.mail.from", "sender@example.test");
        properties.put("management.health.mail.enabled", false);
        properties.put("spring.main.banner-mode", "off");
        try {
            properties.put("campus.storage.dir", Files.createTempDirectory("verification-storage-").toString());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var arguments = properties.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
        return new SpringApplicationBuilder(CampusRatingApplication.class)
                .initializers(context -> context.getBeanFactory().registerSingleton("mailSender", mail))
                .run(arguments);
    }

    private static final class Fixture {
        private final String prefix = UUID.randomUUID().toString();
        private final long faculty;
        private final long group;
        private final long department;

        Fixture(ConfigurableApplicationContext context) {
            var jdbc = context.getBean(JdbcTemplate.class);
            faculty = jdbc.queryForObject("insert into faculty(code,name) values (?,?) returning id", Long.class, prefix + "f", "Факультет");
            group = jdbc.queryForObject("insert into study_group(code,name,faculty_id,admission_year) values (?,?,?,?) returning id", Long.class, prefix + "g", "Группа", faculty, 2026);
            department = jdbc.queryForObject("insert into department(code,name,faculty_id) values (?,?,?) returning id", Long.class, prefix + "d", "Кафедра", faculty);
        }

        String email(String suffix) { return prefix + "-" + suffix + "@example.test"; }
    }

    private static final class CapturingSender extends JavaMailSenderImpl {
        private final List<SimpleMailMessage> messages = new CopyOnWriteArrayList<>();

        @Override
        public void send(SimpleMailMessage... simpleMessages) {
            for (var message : simpleMessages) messages.add(new SimpleMailMessage(message));
        }

        String token(String address) {
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> messages.stream().anyMatch(m -> m.getTo() != null && List.of(m.getTo()).contains(address)));
            String body = messages.stream().filter(m -> m.getTo() != null && List.of(m.getTo()).contains(address)).reduce((a, b) -> b).orElseThrow().getText();
            var match = Pattern.compile(Pattern.quote("https://campus.example.test" + CONFIRM) + "([A-Za-z0-9_-]+)").matcher(body);
            assertThat(match.find()).withFailMessage("Expected confirmation link in %s", body).isTrue();
            return match.group(1);
        }
    }
}

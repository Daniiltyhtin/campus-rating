package ru.campusrating.identity;

import ru.campusrating.support.UserNameFixture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.campusrating.ledger.LedgerApi;

import static org.assertj.core.api.Assertions.assertThat;

class DemoIdentityLifecycleIT {
    @Test
    void demoRestartsReplaceTransientRegistrationAndRestoreAllThreeDemoLogins() {
        try (var database = new PostgreSQLContainer("postgres:18.6-alpine")) {
            database.start();
            try (var first = IdentityLifecycleSupport.start(database, "demo", Map.of())) {
                assertDemoAccounts(first);
                var browser = IdentityLifecycleSupport.browser(first);
                var transientUser = browser.post("/api/v1/auth/register/student", Map.of("lastName", UserNameFixture.last("Временный Студент"), "firstName", UserNameFixture.first("Временный Студент"), "middleName", UserNameFixture.middleOrEmpty("Временный Студент"), "email", "transient-demo@example.test", "password", "Synthetic-transient-42", "facultyId", 1, "groupId", 11));
                assertThat(transientUser.status()).isEqualTo(201);
            }
            try (var restarted = IdentityLifecycleSupport.start(database, "demo", Map.of())) {
                var jdbc = restarted.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject("select count(*) from app_user where email='transient-demo@example.test'", Integer.class)).isZero();
                assertDemoAccounts(restarted);
                assertThat(jdbc.queryForObject("select count(*) from app_user where id in (1001,1002,1003,1004,2001,2002,3001)",
                        Integer.class)).isEqualTo(7);
                assertThat(jdbc.queryForObject("select count(*) from teacher_approval where teacher_id=2001 and approved_by is null and approved_at is not null",
                        Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from teacher_approval where teacher_id=2002", Integer.class)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from ledger_entry where student_id=1001 and kind='INITIAL'",
                        Integer.class)).isEqualTo(1);
                assertThat(restarted.getBean(LedgerApi.class).reconcile().mismatches()).isEmpty();
            }
        }
    }

    private void assertDemoAccounts(org.springframework.context.ConfigurableApplicationContext context) {
        var credentials = List.of(
                List.of("smirnova@student.campus.test", "demo-student-1", "STUDENT"),
                List.of("petrov@campus.test", "demo-teacher-1", "TEACHER"),
                List.of("admin@campus.test", "demo-admin-1", "ADMIN"));
        for (var values : credentials) {
            var browser = IdentityLifecycleSupport.browser(context);
            var config = browser.get("/api/v1/auth/config");
            assertThat(config.status()).isEqualTo(200);
            assertThat(config.body().path("demo").asBoolean()).isTrue();
            assertThat(config.body().path("emailVerification").asText()).isEqualTo("OFF");
            assertThat(browser.post("/api/v1/auth/login", Map.of("email", values.get(0), "password", values.get(1))).status()).isEqualTo(200);
            var me = browser.get("/api/v1/auth/me");
            assertThat(me.status()).isEqualTo(200);
            assertThat(me.body().path("role").asText()).isEqualTo(values.get(2));
            if (values.get(2).equals("TEACHER")) assertThat(me.body().path("teacherApproved").asBoolean()).isTrue();
            assertThat(browser.post("/api/v1/auth/logout", Map.of()).status()).isEqualTo(204);
            assertThat(browser.get("/api/v1/auth/me").status()).isEqualTo(401);
        }
    }
}

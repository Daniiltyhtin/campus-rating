package ru.campusrating.identity;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class DemoPasswordPrefixIT {
    private static final String PREFIX = "Kx7q9Lm2Pw4Rt6Yz";
    private static final List<List<String>> DEMO_ACCOUNTS = List.of(
            List.of("smirnova@student.campus.test", "student"),
            List.of("kuznetsov@student.campus.test", "student"),
            List.of("novikov@student.campus.test", "student"),
            List.of("orlov@student.campus.test", "student"),
            List.of("ivanova@student.campus.test", "student"),
            List.of("sokolov@student.campus.test", "student"),
            List.of("lebedeva@student.campus.test", "student"),
            List.of("morozov@student.campus.test", "student"),
            List.of("vasileva@student.campus.test", "student"),
            List.of("fedorov@student.campus.test", "student"),
            List.of("petrov@campus.test", "teacher"),
            List.of("volkov@campus.test", "teacher"),
            List.of("admin@campus.test", "admin"));

    private static PostgreSQLContainer database;

    @BeforeAll
    static void startDatabase() {
        database = new PostgreSQLContainer("postgres:18.6-alpine");
        database.start();
    }

    @AfterAll
    static void stopDatabase() { database.stop(); }

    @Test
    void prefixReplacesDemoPartOfEveryDemoPasswordAndSurvivesRestart() {
        try (var context = IdentityLifecycleSupport.start(database, "demo", Map.of("DEMO_PASSWORD_PREFIX", PREFIX))) {
            assertEveryDemoLogin(context, PREFIX);
        }
        try (var restarted = IdentityLifecycleSupport.start(database, "demo", Map.of("DEMO_PASSWORD_PREFIX", PREFIX))) {
            assertEveryDemoLogin(restarted, PREFIX);
            assertThat(restarted.getBean(JdbcTemplate.class)
                    .queryForObject("select count(*) from app_user where email like '%campus.test'", Integer.class))
                    .isEqualTo(DEMO_ACCOUNTS.size());
        }
    }

    @Test
    void readmePasswordsAreRejectedLikeAnyWrongPasswordWhenPrefixIsSet() {
        try (var context = IdentityLifecycleSupport.start(database, "demo", Map.of("DEMO_PASSWORD_PREFIX", PREFIX))) {
            var wrong = login(context, "admin@campus.test", "definitely-wrong-1");
            assertThat(wrong.status()).isEqualTo(401);
            for (var readme : List.of(List.of("admin@campus.test", "demo-admin-1"),
                    List.of("smirnova@student.campus.test", "demo-student-1"))) {
                var reply = login(context, readme.get(0), readme.get(1));
                assertThat(reply.status()).isEqualTo(wrong.status());
                assertThat(reply.body().path("code").asText()).isEqualTo(wrong.body().path("code").asText());
                assertThat(reply.body().path("detail").asText()).isEqualTo(wrong.body().path("detail").asText());
            }
        }
    }

    @Test
    void readmePasswordsWorkWithoutPrefixAndWithEmptyPrefix() {
        try (var context = IdentityLifecycleSupport.start(database, "demo", Map.of())) {
            assertEveryDemoLogin(context, "demo");
        }
        try (var context = IdentityLifecycleSupport.start(database, "demo", Map.of("DEMO_PASSWORD_PREFIX", ""))) {
            assertEveryDemoLogin(context, "demo");
        }
    }

    @Test
    void prodProfileIgnoresPrefix() {
        try (var context = IdentityLifecycleSupport.start(database, "prod", Map.of("DEMO_PASSWORD_PREFIX", PREFIX,
                "spring.liquibase.drop-first", true,
                "CAMPUS_BOOTSTRAP_ADMIN_EMAIL", "root@example.test",
                "CAMPUS_BOOTSTRAP_ADMIN_PASSWORD", "Bootstrap-admin-42"))) {
            assertThat(context.containsBean("demoPasswords")).isFalse();
            assertThat(login(context, "root@example.test", "Bootstrap-admin-42").status()).isEqualTo(200);
        }
    }

    private static void assertEveryDemoLogin(ConfigurableApplicationContext context, String prefix) {
        for (var account : DEMO_ACCOUNTS) {
            var password = prefix + "-" + account.get(1) + "-1";
            assertThat(login(context, account.get(0), password).status()).as(account.get(0)).isEqualTo(200);
        }
    }

    private static IdentityAcceptanceSupport.Reply login(ConfigurableApplicationContext context, String email, String password) {
        return IdentityLifecycleSupport.browser(context).post("/api/v1/auth/login", Map.of("email", email, "password", password));
    }
}

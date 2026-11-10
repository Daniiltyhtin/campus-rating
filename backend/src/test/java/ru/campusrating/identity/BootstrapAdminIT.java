package ru.campusrating.identity;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapAdminIT {
    @Test
    void cleanProdCreatesOneAdminAndRestartIgnoresNewBootstrapCredentials() {
        try (var database = new PostgreSQLContainer("postgres:18.6-alpine")) {
            database.start();
            long firstId;
            String firstHash;
            try (var first = IdentityLifecycleSupport.start(database, "prod", Map.of(
                    "CAMPUS_BOOTSTRAP_ADMIN_EMAIL", "bootstrap-admin@example.test",
                    "CAMPUS_BOOTSTRAP_ADMIN_PASSWORD", "Synthetic-bootstrap-42"))) {
                var jdbc = first.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject("select count(*) from app_user where role='ADMIN'", Integer.class)).isEqualTo(1);
                firstId = jdbc.queryForObject("select id from app_user where role='ADMIN'", Long.class);
                firstHash = jdbc.queryForObject("select password_hash from app_user where id=?", String.class, firstId);
                assertThat(firstHash).startsWith("$2").isNotEqualTo("Synthetic-bootstrap-42");
                var admin = IdentityLifecycleSupport.browser(first);
                assertThat(admin.post("/api/v1/auth/login", Map.of("email", "bootstrap-admin@example.test",
                        "password", "Synthetic-bootstrap-42")).status()).isEqualTo(200);
                assertThat(admin.get("/api/v1/auth/me").body().path("mustChangePassword").asBoolean()).isTrue();
                var forbidden = admin.get("/api/v1/admin/ledger/reconciliation");
                assertThat(forbidden.status()).isEqualTo(403);
                assertThat(forbidden.body().path("code").asText()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
            }
            try (var restarted = IdentityLifecycleSupport.start(database, "prod", Map.of(
                    "CAMPUS_BOOTSTRAP_ADMIN_EMAIL", "ignored-bootstrap@example.test",
                    "CAMPUS_BOOTSTRAP_ADMIN_PASSWORD", "Different-bootstrap-43"))) {
                var jdbc = restarted.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject("select count(*) from app_user where role='ADMIN'", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("select id from app_user where role='ADMIN'", Long.class)).isEqualTo(firstId);
                assertThat(jdbc.queryForObject("select password_hash from app_user where id=?", String.class, firstId)).isEqualTo(firstHash);
                var admin = IdentityLifecycleSupport.browser(restarted);
                assertThat(admin.post("/api/v1/auth/login", Map.of("email", "bootstrap-admin@example.test",
                        "password", "Synthetic-bootstrap-42")).status()).isEqualTo(200);
                var ignored = IdentityLifecycleSupport.browser(restarted).post("/api/v1/auth/login", Map.of(
                        "email", "ignored-bootstrap@example.test", "password", "Different-bootstrap-43"));
                assertThat(ignored.status()).isEqualTo(401);
                assertThat(ignored.body().path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
                var config = IdentityLifecycleSupport.browser(restarted).get("/api/v1/auth/config");
                assertThat(config.status()).isEqualTo(200);
                assertThat(config.body().has("demo")).isTrue();
                assertThat(config.body().path("demo").asBoolean()).isFalse();
            }
        }
    }
}

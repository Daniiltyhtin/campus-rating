package ru.campusrating.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import ru.campusrating.support.U13MailTestSupport;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "campus.mail.email-verification=required")
class UserTimesIT extends U13MailTestSupport {
    @BeforeEach void verifyAdministrator() {
        jdbc.update("update app_user set email_verified=true where email=?", email("admin"));
    }

    private JsonNode row(long id) {
        return items(get("/api/v1/admin/users?search=" + prefix)).stream()
                .filter(item -> item.path("id").asLong() == id).findFirst().orElseThrow();
    }

    private void time(JsonNode row, String field, String expected) {
        assertThat(row.has(field)).as(field).isTrue();
        if (expected == null) assertThat(row.path(field).isNull()).isTrue();
        else assertThat(Instant.parse(row.path(field).asText())).isEqualTo(Instant.parse(expected));
    }

    @Test void ae9ImportAndTemporaryPasswordLoginUseApplicationClockAndRetryPreservesCreation() {
        clock.set(Instant.parse("2026-11-13T08:00:00Z"));
        long ivanov = imported("ivanov");
        time(row(ivanov), "createdAt", "2026-11-13T08:00:00Z");
        time(row(ivanov), "lastLoginAt", null);
        String temporary = request(admin, "POST", "/api/v1/users/" + ivanov + "/temporary-password", null, 200).path("password").asText();
        clock.set(Instant.parse("2026-11-13T08:20:00Z"));
        request(browser(), "POST", "/api/v1/auth/login", Map.of("email", email("ivanov"), "password", temporary), 200);
        time(row(ivanov), "lastLoginAt", "2026-11-13T08:20:00Z");
        String csv = "email,full_name,faculty_code,group_code\n" + email("ivanov") + ",Иванов," + prefix + "f1," + prefix + "g1\n";
        assertThat(adminWire().upload("/api/v1/admin/import/STUDENTS", "students.csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), 200).path("skipped").asInt()).isEqualTo(1);
        time(row(ivanov), "createdAt", "2026-11-13T08:00:00Z");
        time(row(ivanov), "lastLoginAt", "2026-11-13T08:20:00Z");
    }

    @Test void registrationOfStudentAndTeacherUsesApplicationClockBeforeFirstLogin() {
        clock.set(Instant.parse("2026-11-13T08:30:00Z"));
        long student = student("new", faculty, group);
        long teacher = teacher("volkov", "Волков Максим Игоревич");
        for (long id : new long[]{student, teacher}) {
            time(row(id), "createdAt", "2026-11-13T08:30:00Z");
            time(row(id), "lastLoginAt", null);
            assertThat(jdbc.queryForObject("select created_at from app_user where id=?", Timestamp.class, id).toInstant()).isEqualTo(clock.instant());
        }
    }

    @Test void ae5RepeatedSuccessfulLoginUpdatesTimeAndWrongPasswordDoesNotChangeIt() {
        long petrov = teacher("petrov", "Петров Павел Петрович");
        jdbc.update("update app_user set email_verified=true where id=?", petrov);
        clock.set(Instant.parse("2026-11-13T06:00:00Z"));
        login("petrov");
        time(row(petrov), "lastLoginAt", "2026-11-13T06:00:00Z");
        clock.set(Instant.parse("2026-11-13T08:00:00Z"));
        login("petrov");
        time(row(petrov), "lastLoginAt", "2026-11-13T08:00:00Z");
        clock.set(Instant.parse("2026-11-13T09:00:00Z"));
        request(browser(), "POST", "/api/v1/auth/login", Map.of("email", email("petrov"), "password", "wrong"), 401);
        time(row(petrov), "lastLoginAt", "2026-11-13T08:00:00Z");
        long volkov = teacher("volkov", "Волков Максим Игоревич");
        request(browser(), "POST", "/api/v1/auth/login", Map.of("email", email("volkov"), "password", "wrong"), 401);
        time(row(volkov), "lastLoginAt", null);
    }

    @Test void requiredEmailRejectionPreservesBothNullAndPreviousLoginTime() {
        long volkov = teacher("volkov", "Волков Максим Игоревич");
        assertThat(get("/api/v1/auth/config").path("emailVerification").asText()).isEqualTo("REQUIRED");
        problem(browser(), "POST", "/api/v1/auth/login", Map.of("email", email("volkov"), "password", PASSWORD), 403, "EMAIL_NOT_VERIFIED");
        time(row(volkov), "lastLoginAt", null);
        jdbc.update("update app_user set email_verified=true where id=?", volkov);
        clock.set(Instant.parse("2026-11-13T06:00:00Z"));
        login("volkov");
        jdbc.update("update app_user set email_verified=false where id=?", volkov);
        clock.set(Instant.parse("2026-11-13T09:00:00Z"));
        problem(browser(), "POST", "/api/v1/auth/login", Map.of("email", email("volkov"), "password", PASSWORD), 403, "EMAIL_NOT_VERIFIED");
        time(row(volkov), "lastLoginAt", "2026-11-13T06:00:00Z");
    }

    @Test void migrationBackfillsExistingSeedUsersWithOneTransactionTime() throws Exception {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var migrations = resolver.getResources("classpath*:db/changelog/identity/*-user-times.sql");
        assertThat(migrations).as("KTD2 user-times migration").hasSize(1);
        String schema = "times_" + prefix.replace("-", "");
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.createStatement().execute("create schema " + schema);
            try {
                connection.setSchema(schema);
                for (String module : java.util.List.of("directory", "identity")) {
                    var resources = resolver.getResources("classpath*:db/changelog/" + module + "/*.sql");
                    java.util.Arrays.sort(resources, java.util.Comparator.comparing(org.springframework.core.io.Resource::getFilename));
                    for (var resource : resources) {
                        if (!resource.getFilename().endsWith("-user-times.sql")) org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection, resource);
                    }
                }
                for (String seed : java.util.List.of("202611090900-demo-directory.sql", "202611091600-demo-users.sql")) {
                    org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                            new org.springframework.core.io.ClassPathResource("db/changelog/demo/" + seed));
                }
                int seedCount;
                try (var statement = connection.createStatement(); var result = statement.executeQuery("select count(*) from app_user")) {
                    result.next(); seedCount = result.getInt(1);
                }
                assertThat(seedCount).isPositive();
                connection.setAutoCommit(false);
                var isolated = new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
                Instant transactionTime = isolated.queryForObject("select transaction_timestamp()", Timestamp.class).toInstant();
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection, migrations[0]);
                var created = isolated.queryForList("select created_at from app_user", Timestamp.class);
                assertThat(created).hasSize(seedCount).allSatisfy(time -> assertThat(time.toInstant()).isEqualTo(transactionTime));
                assertThat(isolated.queryForList("select last_login_at from app_user", Timestamp.class)).hasSize(seedCount).containsOnlyNulls();
                connection.commit();
            } finally {
                if (!connection.getAutoCommit()) connection.rollback();
                connection.setAutoCommit(true);
                connection.setSchema("public");
                connection.createStatement().execute("drop schema " + schema + " cascade");
            }
        }
    }

    @Test void ae9DemoSeedGivesOneCreationTimeAndRerunPreservesIt() throws Exception {
        try (var database = new org.testcontainers.postgresql.PostgreSQLContainer("postgres:18.6-alpine")) {
            database.start();
            try (var app = new org.springframework.boot.builder.SpringApplicationBuilder(ru.campusrating.CampusRatingApplication.class)
                    .profiles("demo").run("--server.port=0", "--campus.mail.email-verification=off",
                            "--campus.penalty.scheduler.enabled=false", "--campus.semester.scheduler.enabled=false",
                            "--campus.storage.dir=" + java.nio.file.Files.createTempDirectory("user-times-"),
                            "--spring.datasource.url=" + database.getJdbcUrl(), "--spring.datasource.username=" + database.getUsername(),
                            "--spring.datasource.password=" + database.getPassword())) {
                var demo = app.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
                var before = demo.queryForList("select id,created_at,last_login_at from app_user where id in (1001,1002,1003,1004,2001,2002,3001) order by id");
                assertThat(before).isNotEmpty();
                assertThat(before.stream().map(item -> item.get("created_at")).distinct().toList()).hasSize(1).doesNotContainNull();
                assertThat(before).allSatisfy(item -> assertThat(item.get("last_login_at")).isNull());
                try (var connection = demo.getDataSource().getConnection()) {
                    org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                            new org.springframework.core.io.ClassPathResource("db/changelog/demo/202611091600-demo-users.sql"));
                }
                assertThat(demo.queryForList("select id,created_at,last_login_at from app_user where id in (1001,1002,1003,1004,2001,2002,3001) order by id")).isEqualTo(before);
            }
        }
    }
}

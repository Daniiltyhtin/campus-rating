package ru.campusrating;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class DemoRecreateIT {
    @ParameterizedTest
    @ValueSource(strings = {"demo", "prod"})
    void restartRecreatesOnlyDemoSchema(String profile) throws IOException {
        var storage = Files.createTempDirectory("campus-recreate-storage-");
        // drop-first must never touch the shared integration-test database.
        try (var postgres = new PostgreSQLContainer("postgres:18.6-alpine")) {
            postgres.start();
            try (var first = start(profile, postgres, storage)) {
                var jdbc = first.getBean(JdbcTemplate.class);
                jdbc.execute("create table u2_probe (id integer primary key)");
                jdbc.update("insert into u2_probe (id) values (42)");
                assertThat(jdbc.queryForObject("select id from u2_probe", Integer.class)).isEqualTo(42);
                Files.writeString(storage.resolve("previous-submission"), "old");
            }
            try (var second = start(profile, postgres, storage)) {
                var jdbc = second.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject(
                        "select to_regclass('public.u2_probe') is not null", Boolean.class))
                        .isEqualTo(profile.equals("prod"));
                assertThat(Files.exists(storage.resolve("previous-submission"))).isEqualTo(profile.equals("prod"));
                if (profile.equals("prod")) {
                    assertThat(jdbc.queryForList("select id from u2_probe", Integer.class))
                            .containsExactly(42);
                }
            }
        }
    }

    private ConfigurableApplicationContext start(String profile, PostgreSQLContainer postgres, Path storage) {
        return new SpringApplicationBuilder(CampusRatingApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(profile)
                .run("--campus.mail.email-verification=off",
                        "--campus.storage.dir=" + storage,
                        "--CAMPUS_BOOTSTRAP_ADMIN_EMAIL=schema-admin@example.test",
                        "--CAMPUS_BOOTSTRAP_ADMIN_PASSWORD=Synthetic-schema-admin-42",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword());
    }
}

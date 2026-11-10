package ru.campusrating;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.campusrating.support.MutableClock;
import ru.campusrating.support.PostgresIntegrationTest;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SmokeIT extends PostgresIntegrationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    @Autowired
    private Clock clock;

    @Test
    void healthIsUp() throws Exception {
        var response = get("/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonMapper.builder().build().readTree(response.body()).path("status").asText())
                .isEqualTo("UP");
    }

    @Test
    void liquibaseInitializesEmptyChangelogWithDefaultContext() {
        assertThat(environment.getProperty("spring.liquibase.contexts")).isEqualTo("default");
        assertThat(jdbc.queryForObject(
                "select to_regclass('public.databasechangelog') is not null", Boolean.class)).isTrue();
    }

    @Test
    void protectedApiRequiresAuthentication() throws Exception {
        var response = get("/api/v1/does-not-exist");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("content-type").orElseThrow())
                .startsWith("application/problem+json");
        var error = JsonMapper.builder().build().readTree(response.body());
        assertThat(error.path("status").asInt()).isEqualTo(401);
        assertThat(error.path("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(error.path("instance").asText()).isEqualTo("/api/v1/does-not-exist");
    }

    @Test
    void contextUsesMutableClock() {
        assertThat(clock).isInstanceOf(MutableClock.class);
        var before = clock.instant();
        ((MutableClock) clock).advance(Duration.ofHours(1));
        assertThat(clock.instant()).isEqualTo(before.plus(Duration.ofHours(1)));
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}

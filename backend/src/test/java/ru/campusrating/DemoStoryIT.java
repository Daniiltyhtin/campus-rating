package ru.campusrating;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.campusrating.ledger.LedgerApi;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class DemoStoryIT {
    @TempDir
    Path storage;

    @Test
    void demoStoryHasConsistentAccountingAndIsVisibleThroughHttp() {
        // demo drop-first requires a database isolated from the shared IT container.
        try (var database = new PostgreSQLContainer("postgres:18.6-alpine")) {
            database.start();
            try (var app = start(database)) {
                var jdbc = app.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject("select count(*) from app_user where id between 11001 and 11006", Integer.class))
                        .isEqualTo(6);
                assertThat(app.getBean(LedgerApi.class).reconcile().mismatches()).isEmpty();
                assertThat(jdbc.queryForList("select email from app_user where email_verified order by email", String.class))
                        .containsExactly("admin@campus.test", "petrov@campus.test", "smirnova@student.campus.test");
                assertAccounting(jdbc);
                assertHttp(app);
            }
        }
    }

    private ConfigurableApplicationContext start(PostgreSQLContainer database) {
        return new SpringApplicationBuilder(CampusRatingApplication.class).profiles("demo")
                .run("--server.port=0", "--campus.mail.email-verification=off",
                        "--campus.penalty.scheduler.enabled=false", "--campus.semester.scheduler.enabled=false",
                        "--campus.storage.dir=" + storage,
                        "--spring.datasource.url=" + database.getJdbcUrl(),
                        "--spring.datasource.username=" + database.getUsername(),
                        "--spring.datasource.password=" + database.getPassword());
    }

    private void assertAccounting(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("""
                select b.teacher_id, b.group_id, b.month from activity_budget b
                full join (select author_id, group_id,
                    date_trunc('month', confirmed_at at time zone 'Europe/Moscow')::date as month,
                    sum(amount) as amount from activity_award group by 1,2,3) a
                on (b.teacher_id,b.group_id,b.month)=(a.author_id,a.group_id,a.month)
                where b.spent is distinct from a.amount
                """)).isEmpty();
        assertThat(jdbc.queryForObject("select spent from activity_budget where teacher_id=2001 and group_id=11",
                Integer.class)).isEqualTo(950);
        assertThat(jdbc.queryForList("""
                select a.id from assignment a left join ledger_entry e
                on e.idempotency_key like 'penalty:' || a.id || ':%' and e.kind='PENALTY'
                group by a.id having a.charged <> -coalesce(sum(e.amount),0)
                """)).isEmpty();
        assertThat(jdbc.queryForList("""
                select a.id from assignment a join checkpoint c on c.id=a.checkpoint_id
                left join ledger_entry e on e.idempotency_key like 'penalty:' || a.id || ':%'
                group by a.id, c.cost having a.charged>c.cost or
                    a.last_penalty_slot is distinct from max(split_part(e.idempotency_key, ':', 3)::integer)
                """)).isEmpty();
        assertThat(jdbc.queryForList("""
                select a.id from assignment a left join lateral
                    (select * from submission where assignment_id=a.id order by submitted_at desc, id desc limit 1) s on true
                where (a.state in ('SUBMITTED','ACCEPTED') and
                    (s.id is null or s.returned_at is not null or s.withdrawn_at is not null))
                    or (a.state='OPEN' and s.id is not null and s.returned_at is null and s.withdrawn_at is null)
                    or ((a.state='ACCEPTED') <> (a.accepted_at is not null))
                """)).isEmpty();
        assertThat(jdbc.queryForList("""
                select w.id from activity_award w left join ledger_entry e on e.id=w.ledger_entry_id
                where e.id is null or e.amount<>w.amount or e.student_id<>w.student_id
                    or e.currency<>w.currency or e.author_id<>w.author_id
                    or e.source_id<>w.activity_id or e.kind<>'ACTIVITY_AWARD'
                    or e.idempotency_key<>'award:' || w.activity_id || ':' || w.student_id
                """)).isEmpty();
    }

    private void assertHttp(ConfigurableApplicationContext app) {
        int port = app.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        var student = new Browser(port, "smirnova@student.campus.test", "demo-student-1");
        var tasks = student.get("/api/v1/me/assignments?view=active").path("items");
        assertThat(tasks).anySatisfy(task -> {
            assertThat(task.path("status").asText()).isEqualTo("OVERDUE");
            assertThat(task.path("penalty").path("charged").asInt()).isEqualTo(60);
        });
        var awards = student.get("/api/v1/feed/awards").path("items");
        assertThat(awards).isNotEmpty();
        var teacher = new Browser(port, "petrov@campus.test", "demo-teacher-1");
        var rating = teacher.get("/api/v1/groups/11/ratings?sort=AR").path("items");
        var balances = new ArrayList<Integer>();
        rating.forEach(row -> balances.add(row.path("balances").path("AR").asInt()));
        assertThat(balances).hasSize(6).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(balances).contains(1000, 940);
        var table = teacher.get("/api/v1/checkpoints/21001/assignments");
        assertThat(table.path("statusCounts").path("OVERDUE").asInt()).isPositive();
        assertThat(table.path("statusCounts").path("SUBMITTED").asInt()).isPositive();
        assertThat(table.path("statusCounts").path("ACCEPTED").asInt()).isEqualTo(1);
        var returned = teacher.get("/api/v1/assignments/22006");
        assertThat(returned.path("state").asText()).isEqualTo("OPEN");
        assertThat(returned.path("returnComment").asText()).isEqualTo("Добавьте выводы");
    }

    private static final class Browser {
        private final RestTestClient client;

        Browser(int port, String email, String password) {
            var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            client = RestTestClient.bindToServer(new JdkClientHttpRequestFactory(
                            HttpClient.newBuilder().cookieHandler(cookies).build()))
                    .baseUrl("http://127.0.0.1:" + port).build();
            client.get().uri("/api/v1/auth/csrf").exchange().expectStatus().isNoContent();
            var csrf = cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow();
            client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .header("X-XSRF-TOKEN", URLDecoder.decode(csrf.getValue(), StandardCharsets.UTF_8))
                    .body(Map.of("email", email, "password", password)).exchange().expectStatus().isOk();
        }

        JsonNode get(String path) {
            return client.get().uri(path).exchange().expectStatus().isOk().expectBody(JsonNode.class)
                    .returnResult().getResponseBody();
        }
    }
}

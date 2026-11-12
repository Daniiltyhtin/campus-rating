package ru.campusrating.shared;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.campusrating.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import(EventPublicationIT.ListenerConfiguration.class)
class EventPublicationIT extends PostgresIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private Listener listener;
    @Autowired private Environment environment;

    @Test
    void liquibaseOwnsV2SchemaAndCommittedPublicationIsRemovedAfterListener() throws Exception {
        assertThat(environment.getProperty("spring.modulith.events.jdbc.schema-initialization.enabled", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("""
                select count(*) from databasechangelog where author = 'u4' and id = 'event-publication'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                select column_name from information_schema.columns where table_name = 'event_publication'
                """, String.class)).contains("status", "completion_attempts", "last_resubmission_date");
        var event = new Probe(UUID.randomUUID().toString());
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                publisher.publishEvent(event);
                assertThat(publicationCount(event)).isEqualTo(1);
            });
            assertThat(listener.awaitStarted()).isTrue();
            assertThat(publicationCount(event)).isEqualTo(1);
        } finally {
            listener.release();
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(publicationCount(event)).isZero());
    }

    @Test
    void rolledBackPublicationDoesNotRemainInRegistry() {
        var event = new Probe(UUID.randomUUID().toString());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            publisher.publishEvent(event);
            assertThat(publicationCount(event)).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(publicationCount(event)).isZero();
    }

    private int publicationCount(Probe event) {
        return jdbc.queryForObject("select count(*) from event_publication where serialized_event like ?",
                Integer.class, "%" + event.token() + "%");
    }


    public record Probe(String token) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ListenerConfiguration {
        @Bean Listener probeListener() { return new Listener(); }
    }

    static class Listener {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        public boolean awaitStarted() throws InterruptedException {
            return started.await(10, TimeUnit.SECONDS);
        }

        public void release() {
            release.countDown();
        }

        @ApplicationModuleListener
        public void on(Probe event) throws InterruptedException {
            started.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Listener was not released");
            }
        }
    }
}

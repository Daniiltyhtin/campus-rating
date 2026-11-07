package ru.campusrating.support;

import java.time.Instant;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "campus.mail.email-verification=off")
@Import(PostgresIntegrationTest.TimeConfiguration.class)
public abstract class PostgresIntegrationTest {
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    static {
        POSTGRES.start();
    }

    // final: иначе Spring Framework 7.1 примет класс за конфигурацию теста по умолчанию.
    @TestConfiguration(proxyBeanMethods = false)
    static final class TimeConfiguration {
        @Bean
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-11-07T09:00:00Z"));
        }
    }
}

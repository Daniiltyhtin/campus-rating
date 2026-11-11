package ru.campusrating.support;

import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "campus.mail.email-verification=off")
@Import(TestClockConfiguration.class)
public abstract class PostgresIntegrationTest {
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void storageDirectory(DynamicPropertyRegistry properties) throws IOException {
        var directory = Files.createTempDirectory("campus-it-storage-");
        properties.add("campus.storage.dir", directory::toString);
    }

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(TestClockConfiguration.INITIAL_INSTANT);
    }
}

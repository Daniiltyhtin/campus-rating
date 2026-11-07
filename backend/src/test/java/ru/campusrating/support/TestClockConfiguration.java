package ru.campusrating.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
class TestClockConfiguration {
    static final Instant INITIAL_INSTANT = Instant.parse("2026-11-07T09:00:00Z");

    @Bean
    MutableClock testClock() {
        return new MutableClock(INITIAL_INSTANT);
    }
}

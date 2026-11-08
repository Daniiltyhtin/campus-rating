package ru.campusrating.shared;

import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClockConfigTest {
    @Test
    void defaultClockUsesUtc() {
        var clock = new ClockConfig().clock();
        assertThat(clock).isNotNull();
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}

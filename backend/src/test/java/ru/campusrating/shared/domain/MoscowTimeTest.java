package ru.campusrating.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoscowTimeTest {
    @ParameterizedTest
    @CsvSource({
        "2026-10-31T20:59:59Z, 2026-11-01, 2026-10",
        "2026-10-31T21:00:00Z, 2026-11-01, 2026-11",
        "2026-08-31T21:00:00Z, 2026-09-01, 2026-09",
        "2026-12-31T21:00:00Z, 2027-01-01, 2027-01"
    })
    void convertsInstantToMoscowCalendar(String instant, LocalDate date, YearMonth month) {
        assertThat(MoscowTime.date(Instant.parse(instant))).isEqualTo(date);
        assertThat(MoscowTime.month(Instant.parse(instant))).isEqualTo(month);
    }
}

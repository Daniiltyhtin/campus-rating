package ru.campusrating.shared.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

public final class MoscowTime {
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    private MoscowTime() {}

    public static LocalDate date(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    public static YearMonth month(Instant instant) {
        return YearMonth.from(date(instant));
    }
}

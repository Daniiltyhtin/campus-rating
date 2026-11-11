package ru.campusrating.activity.internal;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;

record MonthRange(Instant from, Instant to) {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    static MonthRange of(YearMonth month) {
        var from = month.atDay(1).atStartOfDay(MOSCOW).toInstant();
        var to = month.plusMonths(1).atDay(1).atStartOfDay(MOSCOW).toInstant();
        return new MonthRange(from, to);
    }
}

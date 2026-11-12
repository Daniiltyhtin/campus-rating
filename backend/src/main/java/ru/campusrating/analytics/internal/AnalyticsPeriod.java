package ru.campusrating.analytics.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import ru.campusrating.shared.error.FieldError;
import ru.campusrating.shared.error.ValidationFailedException;

public record AnalyticsPeriod(LocalDate from, LocalDate to) {
    public static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final String MESSAGE = "Допустимо не больше 366 дней, «С» не позже «По» и «По» не позже сегодня";

    public static AnalyticsPeriod parse(String from, String to, LocalDate today) {
        var first = date(from, today.minusDays(29), "from");
        var last = date(to, today, "to");
        if (last.isAfter(today)) throw invalid("to");
        if (first.isAfter(last) || ChronoUnit.DAYS.between(first, last) >= 366) throw invalid("from");
        return new AnalyticsPeriod(first, last);
    }

    private static LocalDate date(String value, LocalDate fallback, String field) {
        if (value == null) return fallback;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalid(field);
        }
    }

    private static ValidationFailedException invalid(String field) {
        return new ValidationFailedException(MESSAGE, List.of(new FieldError(field, "INVALID", MESSAGE)));
    }

    public static Instant end(LocalDate day) {
        return day.plusDays(1).atStartOfDay(MOSCOW).toInstant().minusNanos(1);
    }
}

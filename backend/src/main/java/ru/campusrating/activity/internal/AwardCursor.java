package ru.campusrating.activity.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

record AwardCursor(Instant confirmedAt, long id, String filter) {
    private static final Instant LAST = Instant.parse("9999-12-31T23:59:59.999999Z");

    AwardCursor {
        if (id <= 0 || confirmedAt.isBefore(Instant.EPOCH) || confirmedAt.isAfter(LAST)) {
            throw new IllegalArgumentException("Cursor requires positive id and time within 1970..9999");
        }
    }

    String encode() {
        var text = id + "|" + confirmedAt + "|" + filter;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    static AwardCursor decode(String encoded, String expectedFilter) {
        var parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|", 3);
        if (parts.length != 3 || !parts[2].equals(expectedFilter)) {
            throw new IllegalArgumentException("Cursor belongs to another list");
        }
        return new AwardCursor(parseInstant(parts[1]), Long.parseLong(parts[0]), expectedFilter);
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Cursor time is invalid", exception);
        }
    }
}

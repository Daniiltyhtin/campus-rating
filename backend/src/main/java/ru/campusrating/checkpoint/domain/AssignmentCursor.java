package ru.campusrating.checkpoint.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

public record AssignmentCursor(String key, long id, String filter) {
    public AssignmentCursor {
        if (key == null || key.isEmpty() || id <= 0 || filter == null || filter.isEmpty()) {
            throw new IllegalArgumentException("Cursor requires key, positive id and filter");
        }
    }

    public Instant acceptedAt() {
        var instant = Instant.parse(key);
        if (instant.isBefore(Instant.EPOCH)
                || instant.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) {
            throw new IllegalArgumentException("Cursor timestamp outside 1970..9999");
        }
        return instant;
    }

    public String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((filter + "\n" + id + "\n" + key).getBytes(StandardCharsets.UTF_8));
    }

    public static AssignmentCursor decode(String encoded, String expectedFilter) {
        var parts =
                new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
                        .split("\n", 3);
        if (parts.length != 3 || !parts[0].equals(expectedFilter)) {
            throw new IllegalArgumentException("Invalid cursor format or filter");
        }
        return new AssignmentCursor(parts[2], Long.parseLong(parts[1]), expectedFilter);
    }
}

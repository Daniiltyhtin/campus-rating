package ru.campusrating.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

public record LedgerCursor(Instant createdAt, long id) {
    public LedgerCursor {
        if (createdAt == null || id <= 0) {
            throw new IllegalArgumentException("Cursor requires timestamp and positive entry id");
        }
    }

    public String encode() {
        long micros = Math.addExact(Math.multiplyExact(createdAt.getEpochSecond(), 1_000_000), createdAt.getNano() / 1_000);
        return Base64.getUrlEncoder().withoutPadding().encodeToString((micros + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static LedgerCursor decode(String encoded) {
        var parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split(":", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid cursor format");
        }
        long micros = Long.parseLong(parts[0]);
        var instant = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1_000);
        return new LedgerCursor(instant, Long.parseLong(parts[1]));
    }
}

package ru.campusrating.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import ru.campusrating.ledger.Currency;

public record LedgerCursor(Instant createdAt, long id, String filter) {
    public static final String AWARDS = "awards";
    private static final long MAX_MICROS = 253402300799999999L;

    public LedgerCursor {
        if (createdAt == null || id <= 0 || filter == null) {
            throw new IllegalArgumentException("Cursor requires timestamp, positive entry id and filter");
        }
    }

    public static String historyFilter(long studentId, Optional<Currency> currency) {
        return "history/" + studentId + "/" + currency.map(Enum::name).orElse("ALL");
    }

    public String encode() {
        long micros = Math.addExact(Math.multiplyExact(createdAt.getEpochSecond(), 1_000_000), createdAt.getNano() / 1_000);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((filter + ":" + micros + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static LedgerCursor decode(String encoded, String expectedFilter) {
        var parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split(":", -1);
        if (parts.length != 3 || !parts[0].equals(expectedFilter)) {
            throw new IllegalArgumentException("Invalid cursor format or filter");
        }
        long micros = Long.parseLong(parts[1]);
        if (micros < 0 || micros > MAX_MICROS) {
            throw new IllegalArgumentException("Cursor timestamp outside 1970..9999");
        }
        var instant = Instant.ofEpochSecond(micros / 1_000_000, micros % 1_000_000 * 1_000);
        return new LedgerCursor(instant, Long.parseLong(parts[2]), expectedFilter);
    }
}

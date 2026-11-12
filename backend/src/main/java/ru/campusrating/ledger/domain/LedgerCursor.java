package ru.campusrating.ledger.domain;

import java.time.Instant;
import java.util.Optional;
import ru.campusrating.ledger.Currency;
import ru.campusrating.shared.domain.TimeIdCursor;

public record LedgerCursor(Instant createdAt, long id, String filter) {
    public static final String AWARDS = "awards";

    public LedgerCursor {
        if (createdAt == null || id <= 0 || filter == null) {
            throw new IllegalArgumentException("Cursor requires timestamp, positive entry id and filter");
        }
    }

    public static String historyFilter(long studentId, Optional<Currency> currency) {
        return "history/" + studentId + "/" + currency.map(Enum::name).orElse("ALL");
    }

    public String encode() {
        return new TimeIdCursor(createdAt, id, filter).encode();
    }

    public static LedgerCursor decode(String encoded, String expectedFilter) {
        var cursor = TimeIdCursor.decode(encoded, expectedFilter);
        return new LedgerCursor(cursor.createdAt(), cursor.id(), expectedFilter);
    }
}

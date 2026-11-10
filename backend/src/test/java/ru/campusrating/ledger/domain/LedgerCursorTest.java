package ru.campusrating.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.campusrating.ledger.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LedgerCursorTest {
    @Test
    void roundTripPreservesMicrosecondsIdAndFilter() {
        for (var filter : new String[] {LedgerCursor.historyFilter(1, Optional.empty()),
                LedgerCursor.historyFilter(1, Optional.of(Currency.AR)), LedgerCursor.AWARDS}) {
            var cursor = new LedgerCursor(Instant.parse("2026-11-09T08:00:00.123456Z"), 123, filter);
            assertThat(LedgerCursor.decode(cursor.encode(), filter)).isEqualTo(cursor);
            assertThat(cursor.encode()).doesNotContain("=", "+", "/");
        }
    }

    @Test
    void rejectsDifferentStudentCurrencyAndList() {
        var filter = LedgerCursor.historyFilter(1, Optional.of(Currency.AR));
        var cursor = new LedgerCursor(Instant.EPOCH, 1, filter).encode();
        for (var other : new String[] {LedgerCursor.historyFilter(2, Optional.of(Currency.AR)),
                LedgerCursor.historyFilter(1, Optional.of(Currency.BOA)),
                LedgerCursor.historyFilter(1, Optional.empty()), LedgerCursor.AWARDS}) {
            assertThatIllegalArgumentException().isThrownBy(() -> LedgerCursor.decode(cursor, other));
        }
    }

    @Test
    void acceptsTimeBoundaries() {
        for (var instant : new Instant[] {Instant.EPOCH, Instant.parse("9999-12-31T23:59:59.999999Z")}) {
            var cursor = new LedgerCursor(instant, 1, LedgerCursor.AWARDS);
            assertThat(LedgerCursor.decode(cursor.encode(), LedgerCursor.AWARDS)).isEqualTo(cursor);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "1:2:3", "awards:1", "awards::2", "awards:1:",
            "awards:abc:2", "awards:1:abc", "awards:1:0", "awards:1:-1", "awards:999999999999999999999999:2",
            "awards:-1:1", "awards:253402300800000000:1", "awards:9223372036854775807:1", "awards:-9223372036854775808:1"})
    void rejectsForeignPayloadAndOutOfRangeTime(String payload) {
        var encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerCursor.decode(encoded, LedgerCursor.AWARDS));
    }

    @Test
    void rejectsMalformedBase64AndMissingValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerCursor.decode("%%%", LedgerCursor.AWARDS));
        assertThatIllegalArgumentException().isThrownBy(() -> new LedgerCursor(null, 1, LedgerCursor.AWARDS));
        assertThatIllegalArgumentException().isThrownBy(() -> new LedgerCursor(Instant.EPOCH, 1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new LedgerCursor(Instant.EPOCH, 0, LedgerCursor.AWARDS));
    }
}

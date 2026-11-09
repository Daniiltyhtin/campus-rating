package ru.campusrating.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LedgerCursorTest {
    @Test
    void roundTripPreservesPostgresMicrosecondsAndId() {
        var cursor = new LedgerCursor(Instant.parse("2026-11-09T08:00:00.123456Z"), 123);
        assertThat(LedgerCursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThat(new String(Base64.getUrlDecoder().decode(cursor.encode()), StandardCharsets.UTF_8))
                .isEqualTo("1794211200123456:123");
        assertThat(cursor.encode()).doesNotContain("=", "+", "/");
    }

    @Test
    void handlesEpochAndInstantsBeforeEpoch() {
        for (var instant : new Instant[] {Instant.EPOCH, Instant.parse("1969-12-31T23:59:59.999999Z")}) {
            var cursor = new LedgerCursor(instant, 1);
            assertThat(LedgerCursor.decode(cursor.encode())).isEqualTo(cursor);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "1:2:3", "1", ":2", "1:", "abc:2", "1:abc", "1:0", "1:-1", "999999999999999999999999:2"})
    void rejectsForeignPayload(String payload) {
        var encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerCursor.decode(encoded));
    }

    @Test
    void rejectsMalformedBase64AndMissingValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerCursor.decode("%%%"));
        assertThatIllegalArgumentException().isThrownBy(() -> new LedgerCursor(null, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new LedgerCursor(Instant.EPOCH, 0));
    }
}

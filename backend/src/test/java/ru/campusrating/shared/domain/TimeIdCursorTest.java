package ru.campusrating.shared.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeIdCursorTest {
    @Test
    void roundTripKeepsMicrosecondsAndFilter() {
        var cursor = new TimeIdCursor(Instant.parse("2026-11-18T09:00:00.123456Z"), 42, "personal/7");
        assertThat(TimeIdCursor.decode(cursor.encode(), "personal/7")).isEqualTo(cursor);
    }

    @Test
    void foreignFilterIsRejected() {
        var encoded = new TimeIdCursor(Instant.parse("2026-11-18T09:00:00Z"), 42, "personal/7").encode();
        assertThatThrownBy(() -> TimeIdCursor.decode(encoded, "personal/8")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"personal/7:1", "personal/7:-1:5", "personal/7:253402300800000000:5", "personal/7:1:x", "personal/7:1:0"})
    void malformedPayloadIsRejected(String payload) {
        var encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> TimeIdCursor.decode(encoded, "personal/7")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maximumTimestampIsAccepted() {
        var encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("personal/7:253402300799999999:5".getBytes(StandardCharsets.UTF_8));
        assertThat(TimeIdCursor.decode(encoded, "personal/7").createdAt()).isEqualTo(Instant.parse("9999-12-31T23:59:59.999999Z"));
    }

    @Test
    void constructorRejectsMissingParts() {
        assertThatThrownBy(() -> new TimeIdCursor(null, 1, "f")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeIdCursor(Instant.EPOCH, 0, "f")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeIdCursor(Instant.EPOCH, 1, null)).isInstanceOf(IllegalArgumentException.class);
    }
}

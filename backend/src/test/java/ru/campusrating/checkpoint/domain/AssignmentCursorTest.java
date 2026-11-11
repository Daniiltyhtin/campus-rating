package ru.campusrating.checkpoint.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class AssignmentCursorTest {
    @Test
    void roundTripsTimestampAndUnicodeNameIncludingSeparators() {
        for (String key : new String[] {"2026-11-11T12:00:00.123456Z", "Иванов: Иван\nИванович"}) {
            var cursor = new AssignmentCursor(key, 42, "checkpoint/12/OVERDUE/3");
            assertThat(AssignmentCursor.decode(cursor.encode(), cursor.filter())).isEqualTo(cursor);
            assertThat(cursor.encode()).doesNotContain("=", "Иванов", "checkpoint");
        }
    }

    @Test
    void rejectsForeignStudentCheckpointStatusAndGroup() {
        for (String filter :
                new String[] {"completed/1", "checkpoint/1/ALL/ALL", "checkpoint/1/OVERDUE/2"}) {
            var cursor = new AssignmentCursor("key", 1, filter);
            assertThatThrownBy(() -> AssignmentCursor.decode(cursor.encode(), filter + "other"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "!",
                "a",
                "bm9wZQ",
                "ZmlsdGVyCjAKa2V5",
                "ZmlsdGVyCi0xCmtleQ",
                "ZmlsdGVyCm5hbgprZXk",
                "ZmlsdGVyCjEK"
            })
    void rejectsMalformedCursor(String encoded) {
        assertThatThrownBy(() -> AssignmentCursor.decode(encoded, "filter"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingPartsAndOverflow() {
        for (String value : new String[] {"filter\n1", "filter\n9223372036854775808\nkey"}) {
            String encoded =
                    Base64.getUrlEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> AssignmentCursor.decode(encoded, "filter"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void completedTimestampPreservesMicrosecondsAndSupportedBounds() {
        for (String key :
                new String[] {
                    "1970-01-01T00:00:00Z",
                    "2026-11-11T12:00:00.123456Z",
                    "9999-12-31T23:59:59.999999Z"
                }) {
            assertThat(new AssignmentCursor(key, 1, "completed/1").acceptedAt())
                    .isEqualTo(Instant.parse(key));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "name",
                "1969-12-31T23:59:59.999999Z",
                "+10000-01-01T00:00:00Z",
                "9999-12-31T23:59:59.999999001Z"
            })
    void rejectsInvalidCompletedTimestamp(String key) {
        assertThatThrownBy(() -> new AssignmentCursor(key, 1, "completed/1").acceptedAt())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void requiresKeyPositiveIdAndFilter() {
        assertThatThrownBy(() -> new AssignmentCursor(null, 1, "filter"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssignmentCursor("", 1, "filter"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssignmentCursor("key", 0, "filter"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssignmentCursor("key", 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssignmentCursor("key", 1, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

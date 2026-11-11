package ru.campusrating.checkpoint.domain;

import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PenaltySlotTest {
    private static final Instant DEADLINE = Instant.parse("2026-11-02T12:00:00+03:00");

    @ParameterizedTest
    @CsvSource({"1,0", "500000000,0", "59999999999,0", "60000000000,1",
            "61000000000,1", "121000000000,2"})
    void currentSlot(long nanos, long expected) {
        assertThat(PenaltySlot.current(DEADLINE, 60, DEADLINE.plusNanos(nanos))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,60", "2,120", "5,300", "40000000,2400000000"})
    void slotStart(long slot, long seconds) {
        assertThat(PenaltySlot.start(DEADLINE, 60, slot)).isEqualTo(DEADLINE.plusSeconds(seconds));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void noCurrentSlotUntilAfterDeadline(long seconds) {
        assertThatIllegalArgumentException().isThrownBy(() ->
                PenaltySlot.current(DEADLINE, 60, DEADLINE.plusSeconds(seconds)));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void rejectsInvalidInterval(int interval) {
        assertThatIllegalArgumentException().isThrownBy(() ->
                PenaltySlot.current(DEADLINE, interval, DEADLINE.plusSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> PenaltySlot.start(DEADLINE, interval, 0));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, -10})
    void rejectsNegativeSlot(long slot) {
        assertThatIllegalArgumentException().isThrownBy(() -> PenaltySlot.start(DEADLINE, 60, slot));
    }
}

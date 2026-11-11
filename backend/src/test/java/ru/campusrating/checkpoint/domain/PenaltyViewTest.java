package ru.campusrating.checkpoint.domain;

import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.campusrating.checkpoint.AssignmentState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PenaltyViewTest {
    private static final Instant DEADLINE = Instant.parse("2026-11-02T12:00:00+03:00");

    @ParameterizedTest(name = "example {0}")
    @CsvSource({
            "1, OPEN, -1, 0, , 1000, BEFORE_DEADLINE, 0, 20",
            "2, OPEN, 0, 0, , 1000, BEFORE_DEADLINE, 0, 20",
            "3, OPEN, 90, 40, 1, 960, RUNNING, 120, 20",
            "4, OPEN, 180, 60, 2, 940, CAP_REACHED, ,",
            "5, OPEN, 90, 20, 0, 0, ZERO_BALANCE, 120, 0",
            "6, SUBMITTED, 90, 40, 1, 960, STOPPED, ,",
            "1a, OPEN, 30, 0, , 1000, RUNNING, 0, 20"
    })
    void examples(String example, AssignmentState state, long seconds, int charged, Long lastSlot,
                  int balance, PenaltyPhase phase, Long nextSeconds, Integer amount) {
        var result = PenaltyView.calculate(state, DEADLINE, 60, 20, 60, charged,
                lastSlot, balance, DEADLINE.plusSeconds(seconds));
        assertThat(result).isEqualTo(new PenaltyView(charged, 60, 20, 60, phase,
                nextSeconds == null ? null : DEADLINE.plusSeconds(nextSeconds), amount));
    }

    @ParameterizedTest
    @CsvSource({
            "OPEN, 310, 100, 40, 1, 960, RUNNING, 300, 20",
            "OPEN, 90, 60, 0, , 7, RUNNING, 60, 7",
            "OPEN, 90, 60, 50, , 1000, RUNNING, 60, 10",
            "OPEN, -1, 10, 0, , 0, BEFORE_DEADLINE, 0, 10",
            "OPEN, 90, 60, 61, , 0, CAP_REACHED, ,",
            "OPEN, -1, 60, 60, , 0, CAP_REACHED, ,",
            "SUBMITTED, -1, 60, 60, , 0, STOPPED, ,",
            "ACCEPTED, 90, 60, 60, , 0, STOPPED, ,",
            "OPEN, 90, 60, 20, 1, 0, ZERO_BALANCE, 120, 0",
            "OPEN, 90, 60, 20, 5, 1000, RUNNING, 120, 20",
            "OPEN, 60, 60, 0, 0, 1000, RUNNING, 60, 20"
    })
    void boundariesAndPriority(AssignmentState state, long seconds, int cost, int charged, Long lastSlot,
                               int balance, PenaltyPhase phase, Long nextSeconds, Integer amount) {
        assertThat(PenaltyView.calculate(state, DEADLINE, cost, 20, 60, charged, lastSlot,
                balance, DEADLINE.plusSeconds(seconds)))
                .isEqualTo(new PenaltyView(charged, cost, 20, 60, phase,
                        nextSeconds == null ? null : DEADLINE.plusSeconds(nextSeconds), amount));
    }

    @ParameterizedTest
    @CsvSource({
            "500000000, , 0",
            "61000000000, 0, 60",
            "122000000000, 1, 120",
            "61000000000, 1, 120"
    })
    void nextAtIsStartOfNearestUnprocessedSlot(long nanos, Long lastSlot, long nextSeconds) {
        var result = PenaltyView.calculate(AssignmentState.OPEN, DEADLINE, 60, 20, 60, 0,
                lastSlot, 1000, DEADLINE.plusNanos(nanos));
        assertThat(result.phase()).isEqualTo(PenaltyPhase.RUNNING);
        assertThat(result.nextAt()).isEqualTo(DEADLINE.plusSeconds(nextSeconds));
    }

    @ParameterizedTest
    @CsvSource({"0,20,60,0,0", "1001,20,60,0,0", "60,0,60,0,0", "60,-1,60,0,0",
            "60,20,0,0,0", "60,20,-1,0,0", "60,20,60,-1,0", "60,20,60,0,-1"})
    void rejectsInvalidArguments(int cost, int step, int interval, int charged, int balance) {
        assertThatIllegalArgumentException().isThrownBy(() -> PenaltyView.calculate(
                AssignmentState.OPEN, DEADLINE, cost, step, interval, charged, null, balance, DEADLINE));
    }

    @ParameterizedTest
    @CsvSource({"1,1,1,0,1", "1000,2147483647,1,0,1000", "60,20,60,0,20"})
    void acceptsNumericBoundaries(int cost, int step, int interval, int charged, int amount) {
        assertThat(PenaltyView.calculate(AssignmentState.OPEN, DEADLINE, cost, step, interval,
                charged, null, 1000, DEADLINE.plusNanos(1)).nextAmount()).isEqualTo(amount);
    }
}

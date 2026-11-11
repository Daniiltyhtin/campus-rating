package ru.campusrating.checkpoint.domain;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.campusrating.checkpoint.AssignmentState;

import static org.assertj.core.api.Assertions.*;

class PenaltyPolicyTest {
    private static final Instant DEADLINE = Instant.parse("2026-11-11T09:00:00Z");

    @Test
    void deadlineIsStrictAndFirstNanosecondChargesSlotZero() {
        assertThat(decide(0, null, DEADLINE.minusNanos(1))).isEqualTo(new PenaltyPolicy.Skip());
        assertThat(decide(0, null, DEADLINE)).isEqualTo(new PenaltyPolicy.Skip());
        assertThat(decide(0, null, DEADLINE.plusNanos(1)))
                .isEqualTo(new PenaltyPolicy.Charge(0, 20));
    }

    @Test
    void slotBoundaryUsesPenaltySlot() {
        assertThat(decide(0, null, DEADLINE.plusMillis(59999)))
                .isEqualTo(new PenaltyPolicy.Charge(0, 20));
        assertThat(decide(0, null, DEADLINE.plusSeconds(60)))
                .isEqualTo(new PenaltyPolicy.Charge(1, 20));
    }

    @Test
    void processedAndFutureSlotsAreSkipped() {
        assertThat(decide(20, 1L, DEADLINE.plusSeconds(61))).isEqualTo(new PenaltyPolicy.Skip());
        assertThat(decide(20, 2L, DEADLINE.plusSeconds(61))).isEqualTo(new PenaltyPolicy.Skip());
        assertThat(decide(20, 0L, DEADLINE.plusSeconds(61)))
                .isEqualTo(new PenaltyPolicy.Charge(1, 20));
        assertThat(decide(20, 0L, DEADLINE.plusSeconds(330)))
                .isEqualTo(new PenaltyPolicy.Charge(5, 20));
    }

    @Test
    void nominalIsLimitedByRemainingCost() {
        assertThat(decide(50, null, DEADLINE.plusSeconds(1)))
                .isEqualTo(new PenaltyPolicy.Charge(0, 10));
        assertThat(decide(40, null, DEADLINE.plusSeconds(1)))
                .isEqualTo(new PenaltyPolicy.Charge(0, 20));
        assertThat(decide(60, null, DEADLINE.plusSeconds(1))).isEqualTo(new PenaltyPolicy.Skip());
        assertThat(decide(61, null, DEADLINE.plusSeconds(1))).isEqualTo(new PenaltyPolicy.Skip());
    }

    @ParameterizedTest
    @EnumSource(value = AssignmentState.class, names = {"SUBMITTED", "ACCEPTED"})
    void closedAssignmentsAreSkipped(AssignmentState state) {
        assertThat(PenaltyPolicy.calculate(state, DEADLINE, 60, 20, 60, 0, null,
                DEADLINE.plusSeconds(1))).isEqualTo(new PenaltyPolicy.Skip());
    }

    @Test
    void usesCheckpointIntervalStepAndCost() {
        assertThat(PenaltyPolicy.calculate(AssignmentState.OPEN, DEADLINE, 7, 13, 45, 40,
                1L, DEADLINE.plusSeconds(14))).isEqualTo(new PenaltyPolicy.Charge(2, 5));
    }

    private PenaltyPolicy.Decision decide(int charged, Long last, Instant now) {
        return PenaltyPolicy.calculate(AssignmentState.OPEN, DEADLINE, 60, 20, 60,
                charged, last, now);
    }
}

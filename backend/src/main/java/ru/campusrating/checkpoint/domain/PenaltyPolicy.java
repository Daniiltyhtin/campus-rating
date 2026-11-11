package ru.campusrating.checkpoint.domain;

import java.time.Instant;
import ru.campusrating.checkpoint.AssignmentState;

public final class PenaltyPolicy {
    private PenaltyPolicy() {}

    public sealed interface Decision permits Charge, Skip {}

    public record Charge(long slot, int nominal) implements Decision {}

    public record Skip() implements Decision {}

    public static Decision calculate(AssignmentState state, Instant deadline, int intervalSeconds,
                                      int step, int cost, int charged, Long lastPenaltySlot,
                                      Instant now) {
        if (state != AssignmentState.OPEN || !now.isAfter(deadline) || charged >= cost) {
            return new Skip();
        }
        long slot = PenaltySlot.current(deadline, intervalSeconds, now);
        if (lastPenaltySlot != null && lastPenaltySlot >= slot) {
            return new Skip();
        }
        return new Charge(slot, Math.min(step, cost - charged));
    }
}

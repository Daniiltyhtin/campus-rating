package ru.campusrating.checkpoint.domain;

import java.time.Instant;
import java.util.Objects;
import ru.campusrating.checkpoint.AssignmentState;

public record PenaltyView(int charged, int cap, int step, int intervalSeconds,
                          PenaltyPhase phase, Instant nextAt, Integer nextAmount) {
    public static PenaltyView calculate(AssignmentState state, Instant deadline, int cost, int step,
                                        int intervalSeconds, int charged, Long lastPenaltySlot,
                                        int balance, Instant now) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(deadline);
        Objects.requireNonNull(now);
        validate(cost, step, intervalSeconds, charged, balance);
        PenaltyPhase phase = phase(state, deadline, cost, charged, balance, now);
        return new PenaltyView(charged, cost, step, intervalSeconds, phase,
                nextAt(phase, deadline, intervalSeconds, lastPenaltySlot, now),
                nextAmount(phase, cost, step, charged, balance));
    }

    private static void validate(int cost, int step, int intervalSeconds, int charged, int balance) {
        PenaltySlot.validateInterval(intervalSeconds);
        validateCost(cost);
        if (step <= 0) {
            throw new IllegalArgumentException("Step must be positive");
        }
        if (charged < 0) {
            throw new IllegalArgumentException("Charged must be nonnegative");
        }
        if (balance < 0) {
            throw new IllegalArgumentException("Balance must be nonnegative");
        }
    }

    private static void validateCost(int cost) {
        if (cost < 1 || cost > 1000) {
            throw new IllegalArgumentException("Cost must be between 1 and 1000");
        }
    }

    private static PenaltyPhase phase(AssignmentState state, Instant deadline, int cost,
                                      int charged, int balance, Instant now) {
        if (state != AssignmentState.OPEN) {
            return PenaltyPhase.STOPPED;
        }
        if (charged >= cost) {
            return PenaltyPhase.CAP_REACHED;
        }
        if (!now.isAfter(deadline)) {
            return PenaltyPhase.BEFORE_DEADLINE;
        }
        return balance == 0 ? PenaltyPhase.ZERO_BALANCE : PenaltyPhase.RUNNING;
    }

    private static Instant nextAt(PenaltyPhase phase, Instant deadline, int intervalSeconds,
                                  Long lastPenaltySlot, Instant now) {
        return switch (phase) {
            case STOPPED, CAP_REACHED -> null;
            case BEFORE_DEADLINE -> deadline;
            case ZERO_BALANCE -> PenaltySlot.start(deadline, intervalSeconds,
                    PenaltySlot.current(deadline, intervalSeconds, now) + 1);
            case RUNNING -> nextRunningSlot(deadline, intervalSeconds, lastPenaltySlot, now);
        };
    }

    private static Instant nextRunningSlot(Instant deadline, int intervalSeconds,
                                           Long lastPenaltySlot, Instant now) {
        long current = PenaltySlot.current(deadline, intervalSeconds, now);
        long next = lastPenaltySlot == null || lastPenaltySlot < current ? current : current + 1;
        return PenaltySlot.start(deadline, intervalSeconds, next);
    }

    private static Integer nextAmount(PenaltyPhase phase, int cost, int step, int charged, int balance) {
        return switch (phase) {
            case STOPPED, CAP_REACHED -> null;
            case BEFORE_DEADLINE -> Math.min(step, cost);
            case ZERO_BALANCE -> 0;
            case RUNNING -> Math.min(Math.min(step, cost - charged), balance);
        };
    }
}

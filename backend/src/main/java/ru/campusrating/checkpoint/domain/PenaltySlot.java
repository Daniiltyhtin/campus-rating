package ru.campusrating.checkpoint.domain;

import java.time.Duration;
import java.time.Instant;

public final class PenaltySlot {
    private PenaltySlot() {}

    public static long current(Instant deadline, int intervalSeconds, Instant now) {
        validateInterval(intervalSeconds);
        if (!now.isAfter(deadline)) {
            throw new IllegalArgumentException("Current slot exists only after deadline");
        }
        return Duration.between(deadline, now).getSeconds() / intervalSeconds;
    }

    public static Instant start(Instant deadline, int intervalSeconds, long slot) {
        validateInterval(intervalSeconds);
        if (slot < 0) {
            throw new IllegalArgumentException("Slot must be nonnegative");
        }
        return deadline.plusSeconds(Math.multiplyExact(slot, (long) intervalSeconds));
    }

    static void validateInterval(int intervalSeconds) {
        if (intervalSeconds <= 0) {
            throw new IllegalArgumentException("Interval must be positive");
        }
    }
}

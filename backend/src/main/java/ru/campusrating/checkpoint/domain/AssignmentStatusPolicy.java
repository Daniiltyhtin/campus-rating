package ru.campusrating.checkpoint.domain;

import java.time.Instant;
import java.util.Objects;
import ru.campusrating.checkpoint.AssignmentState;
import ru.campusrating.checkpoint.AssignmentStatus;

public final class AssignmentStatusPolicy {
    private AssignmentStatusPolicy() {}

    public static AssignmentStatus status(AssignmentState state, Instant deadline, Instant now) {
        Objects.requireNonNull(deadline);
        Objects.requireNonNull(now);
        return switch (state) {
            case OPEN -> now.isAfter(deadline) ? AssignmentStatus.OVERDUE : AssignmentStatus.IN_PROGRESS;
            case SUBMITTED -> AssignmentStatus.SUBMITTED;
            case ACCEPTED -> AssignmentStatus.ACCEPTED;
        };
    }
}

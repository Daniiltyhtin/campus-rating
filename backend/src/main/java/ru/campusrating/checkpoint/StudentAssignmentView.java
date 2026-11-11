package ru.campusrating.checkpoint;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record StudentAssignmentView(
        long assignmentId,
        String title,
        Instant deadline,
        AssignmentStatus status,
        int charged,
        @Nullable Instant acceptedAt) {}

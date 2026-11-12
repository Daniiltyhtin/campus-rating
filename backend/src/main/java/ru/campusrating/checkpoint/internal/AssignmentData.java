package ru.campusrating.checkpoint.internal;

import java.time.Instant;
import ru.campusrating.checkpoint.AssignmentState;

record AssignmentData(
        long id,
        long checkpointId,
        long studentId,
        long groupId,
        String studentFullName,
        AssignmentState state,
        int charged,
        Long lastPenaltySlot,
        Instant acceptedAt) {}

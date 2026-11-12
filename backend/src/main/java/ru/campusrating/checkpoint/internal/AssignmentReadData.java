package ru.campusrating.checkpoint.internal;

import java.time.Instant;

record AssignmentReadData(
        AssignmentData assignment,
        CheckpointData checkpoint,
        Instant submittedAt,
        String submissionKind,
        boolean hasSubmissions) {}

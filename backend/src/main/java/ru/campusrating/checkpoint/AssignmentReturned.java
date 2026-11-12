package ru.campusrating.checkpoint;

import java.time.Instant;

public record AssignmentReturned(long submissionId, long assignmentId, long studentId, String checkpointTitle, Instant occurredAt) {}

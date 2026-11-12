package ru.campusrating.checkpoint;

import java.time.Instant;

public record AssignmentCreated(long assignmentId, long studentId, String checkpointTitle, Instant deadline, Instant occurredAt) {}

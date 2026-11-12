package ru.campusrating.checkpoint;

import java.time.Instant;

public record AssignmentAccepted(long assignmentId, long studentId, String checkpointTitle, Instant occurredAt) {}

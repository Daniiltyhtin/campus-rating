package ru.campusrating.checkpoint;

import java.time.Instant;

public record AssignmentSubmitted(long submissionId, long checkpointId, long authorId, String checkpointTitle,
                                  String studentFullName, Instant occurredAt) {}

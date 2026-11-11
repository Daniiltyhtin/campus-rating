package ru.campusrating.checkpoint.internal;

import java.time.Instant;

record CheckpointData(long id, long authorId, String title, String description, Instant deadline, int cost,
                      int penaltyStep, int penaltyIntervalSeconds, Instant createdAt, int submittedCount, int totalCount) {}

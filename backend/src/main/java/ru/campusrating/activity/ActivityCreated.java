package ru.campusrating.activity;

import java.time.Instant;
import java.util.List;

public record ActivityCreated(long activityId, String title, List<Long> studentIds, Instant occurredAt) {}

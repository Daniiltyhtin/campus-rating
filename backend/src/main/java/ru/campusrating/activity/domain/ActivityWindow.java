package ru.campusrating.activity.domain;

import java.time.Instant;

public record ActivityWindow(boolean enrollmentOpen, boolean confirmationOpen) {
    public static ActivityWindow at(ActivityType type, Instant startsAt, Instant now) {
        boolean started = !now.isBefore(startsAt);
        return new ActivityWindow(type == ActivityType.SOCIAL && !started, started);
    }
}

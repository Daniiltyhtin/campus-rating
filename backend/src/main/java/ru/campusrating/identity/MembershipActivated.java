package ru.campusrating.identity;

import java.time.Instant;

public record MembershipActivated(long studentId, long groupId, Instant activatedAt) {}

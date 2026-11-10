package ru.campusrating.identity;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record StudentMember(long studentId, String fullName, long groupId, GroupMembershipState state,
                             @Nullable Instant activatedAt) {}

package ru.campusrating.identity;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record Membership(long groupId, GroupMembershipState state, @Nullable Instant activatedAt) {}

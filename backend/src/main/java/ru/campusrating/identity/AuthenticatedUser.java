package ru.campusrating.identity;

import org.jspecify.annotations.Nullable;

public record AuthenticatedUser(long id, Role role, String fullName, boolean mustChangePassword,
                                boolean teacherApproved, @Nullable Membership membership) {}

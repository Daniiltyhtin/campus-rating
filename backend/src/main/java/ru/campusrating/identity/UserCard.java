package ru.campusrating.identity;

import org.jspecify.annotations.Nullable;

public record UserCard(long id, Role role, String fullName, @Nullable Long groupId, @Nullable Long departmentId) {}

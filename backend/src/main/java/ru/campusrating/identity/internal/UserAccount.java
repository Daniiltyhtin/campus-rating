package ru.campusrating.identity.internal;

import org.jspecify.annotations.Nullable;
import ru.campusrating.identity.Role;

public record UserAccount(long id, String email, Role role, String fullName, @Nullable String passwordHash,
                    boolean mustChangePassword, @Nullable Long departmentId, boolean approved) {}

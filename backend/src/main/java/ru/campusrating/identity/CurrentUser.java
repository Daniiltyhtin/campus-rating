package ru.campusrating.identity;

import java.util.Optional;

public interface CurrentUser {
    AuthenticatedUser require();
    Optional<AuthenticatedUser> find();
}

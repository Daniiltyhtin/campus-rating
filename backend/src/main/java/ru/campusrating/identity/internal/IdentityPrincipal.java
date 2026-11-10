package ru.campusrating.identity.internal;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

final class IdentityPrincipal extends User {
    private final long userId;
    IdentityPrincipal(UserAccount user) {
        super(user.email(), user.passwordHash(), List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
        this.userId = user.id();
    }
    long userId() { return userId; }
}

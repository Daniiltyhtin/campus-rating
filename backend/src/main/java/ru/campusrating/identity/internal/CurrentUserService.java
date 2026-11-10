package ru.campusrating.identity.internal;

import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import ru.campusrating.identity.AuthenticatedUser;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.Membership;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

@Service
class CurrentUserService implements CurrentUser {
    private final IdentityRepository repository;
    private final GroupMembers members;
    CurrentUserService(IdentityRepository repository, GroupMembers members) { this.repository = repository; this.members = members; }
    @Override public Optional<AuthenticatedUser> find() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof IdentityPrincipal principal)) return Optional.empty();
        return repository.byId(principal.userId()).map(user -> new AuthenticatedUser(user.id(), user.role(), user.fullName(),
                user.mustChangePassword(), user.approved(), members.membership(user.id())
                .map(member -> new Membership(member.groupId(), member.state(), member.activatedAt())).orElse(null)));
    }
    @Override public AuthenticatedUser require() {
        return find().orElseThrow(() -> new ApiException(ProblemCode.UNAUTHENTICATED, "Войдите в систему"));
    }
}

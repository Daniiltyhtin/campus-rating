package ru.campusrating.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

@Service
public class SessionService {
    private final IdentityRepository repository;
    private final Passwords passwords;
    private final AuthenticationManager authentication;
    private final SessionAuthenticationStrategy loginStrategy;
    private final SecurityContextRepository contexts;
    private final CookieCsrfTokenRepository csrf;
    private final SessionRegistry registry;
    private final PasswordSetupService setup;
    SessionService(IdentityRepository repository, Passwords passwords, AuthenticationManager authentication,
                   SessionAuthenticationStrategy loginStrategy, SecurityContextRepository contexts,
                   CookieCsrfTokenRepository csrf, SessionRegistry registry,PasswordSetupService setup) {
        this.repository = repository; this.passwords = passwords; this.authentication = authentication;
        this.loginStrategy = loginStrategy; this.contexts = contexts; this.csrf = csrf; this.registry = registry;
        this.setup=setup;
    }

    @Transactional
    public void login(String email, String password, HttpServletRequest request, HttpServletResponse response) {
        passwords.requireByteLimit(password, "password");
        try {
            var result = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
            var principal=(IdentityPrincipal)result.getPrincipal();
            if(setup.required()&&!setup.verified(principal.userId())&&!account(principal.userId()).mustChangePassword())
                throw new ApiException(ProblemCode.EMAIL_NOT_VERIFIED,"Отправить ссылку подтверждения");
            loginStrategy.onAuthentication(result, request, response);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(result);
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            csrf.saveToken(null, request, response);
            repository.recordLogin(principal.userId());
        } catch (AuthenticationException exception) {
            throw new ApiException(ProblemCode.INVALID_CREDENTIALS, "Неверный email или пароль");
        }
    }

    public UserAccount account(long id) {
        return repository.byId(id).orElseThrow(() -> new ApiException(ProblemCode.UNAUTHENTICATED, "Войдите в систему"));
    }

    @Transactional
    public void changePassword(long id, String currentPassword, String newPassword) {
        passwords.requireByteLimit(currentPassword, "currentPassword");
        var user = account(id);
        if (!passwords.matches(currentPassword, user.passwordHash())) throw mismatch();
        passwords.requireByteLimit(newPassword, "newPassword");
        if (passwords.matches(newPassword, user.passwordHash())) throw new ApiException(ProblemCode.PASSWORD_REUSED, "Новый пароль должен отличаться от текущего");
        String hash = passwords.hash(newPassword, "newPassword");
        if (!repository.changePassword(id, user.passwordHash(), hash)) throw mismatch();
    }

    public void expireOthers(long userId, HttpServletRequest request, HttpServletResponse response) {
        String current = request.getSession().getId();
        for (var principal : registry.getAllPrincipals()) {
            if (principal instanceof IdentityPrincipal identity && identity.userId() == userId) {
                registry.getAllSessions(principal, false).stream().filter(session -> !session.getSessionId().equals(current))
                        .forEach(session -> session.expireNow());
            }
        }
        csrf.saveToken(null, request, response);
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session != null) registry.removeSessionInformation(session.getId());
        new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        csrf.saveToken(null, request, response);
    }

    private ru.campusrating.shared.error.ValidationFailedException mismatch() {
        return Passwords.invalid("currentPassword", "MISMATCH", "Текущий пароль указан неверно");
    }
}

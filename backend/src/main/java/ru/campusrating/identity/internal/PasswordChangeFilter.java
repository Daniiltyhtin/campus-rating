package ru.campusrating.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.shared.error.ProblemCode;

final class PasswordChangeFilter extends OncePerRequestFilter {
    private static final Set<String> ALLOWED = Set.of("/api/v1/auth/me", "/api/v1/auth/csrf", "/api/v1/auth/logout", "/api/v1/auth/password");
    private final CurrentUser current;
    private final SecurityProblems problems;
    PasswordChangeFilter(CurrentUser current, SecurityProblems problems) { this.current = current; this.problems = problems; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request.getRequestURI().startsWith("/api/v1/") && !ALLOWED.contains(request.getRequestURI())
                && current.find().map(user -> user.mustChangePassword()).orElse(false)) {
            problems.write(request, response, ProblemCode.PASSWORD_CHANGE_REQUIRED);
            return;
        }
        chain.doFilter(request, response);
    }
}

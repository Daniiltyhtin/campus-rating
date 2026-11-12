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

final class EmailVerificationFilter extends OncePerRequestFilter {
    private static final Set<String> ALLOWED=Set.of("/api/v1/auth/me","/api/v1/auth/config","/api/v1/auth/csrf","/api/v1/auth/password","/api/v1/auth/logout","/api/v1/auth/email/status","/api/v1/auth/email/verification","/api/v1/auth/email/confirmation","/api/v1/auth/password/set");
    private final CurrentUser current;private final PasswordSetupService setup;private final SecurityProblems problems;
    EmailVerificationFilter(CurrentUser current,PasswordSetupService setup,SecurityProblems problems) {this.current=current;this.setup=setup;this.problems=problems;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        if(setup.required()&&request.getRequestURI().startsWith("/api/v1/")&&!ALLOWED.contains(request.getRequestURI())&&current.find().filter(u->!setup.verified(u.id())).isPresent()) {
            problems.write(request,response,ProblemCode.EMAIL_NOT_VERIFIED);return;
        }
        chain.doFilter(request,response);
    }
}

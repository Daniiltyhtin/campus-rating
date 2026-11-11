package ru.campusrating.identity.internal;

import java.util.List;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.shared.error.ProblemCode;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean UserDetailsService identityUsers(IdentityRepository repository) {
        return email -> repository.byEmail(email).filter(user -> user.passwordHash() != null)
                .map(IdentityPrincipal::new).orElseThrow(() -> new UsernameNotFoundException("Unknown credentials"));
    }
    @Bean AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
    @Bean SessionRegistry sessionRegistry() { return new SessionRegistryImpl(); }
    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean CookieCsrfTokenRepository csrfRepository() {
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax").path("/"));
        return repository;
    }
    @Bean SessionAuthenticationStrategy loginStrategy(SessionRegistry registry) {
        return new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(), new RegisterSessionAuthenticationStrategy(registry)));
    }
    @Bean ServletListenerRegistrationBean<HttpSessionEventPublisher> sessionEvents() {
        return new ServletListenerRegistrationBean<>(new HttpSessionEventPublisher());
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contexts,
            CookieCsrfTokenRepository csrf, SessionRegistry registry, CurrentUser current, SecurityProblems problems) throws Exception {
        var concurrent = new ConcurrentSessionFilter(registry, event -> problems.write(event.getRequest(), event.getResponse(), ProblemCode.UNAUTHENTICATED));
        http.formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(contexts))
                .csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> problems.write(request, response, ProblemCode.UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, exception) -> problems.write(request, response,
                                exception instanceof CsrfException ? ProblemCode.CSRF_INVALID : ProblemCode.FORBIDDEN)))
                .authorizeHttpRequests(access -> access
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error", "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/auth/config", "/api/v1/directory/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/register/student", "/api/v1/auth/register/teacher").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/checkpoints", "/api/v1/checkpoints/defaults", "/api/v1/me/accessible-groups",
                                "/api/v1/budgets", "/api/v1/me/awards", "/api/v1/me/awards/summary", "/api/v1/groups/*/ratings").hasRole("TEACHER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/checkpoints/*", "/api/v1/checkpoints/*/assignments").hasAnyRole("TEACHER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/checkpoints", "/api/v1/checkpoints/*/assignments/accept", "/api/v1/assignments/*/return",
                                "/api/v1/activities", "/api/v1/activities/*/participation", "/api/v1/activities/*/participation/preview").hasRole("TEACHER")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/checkpoints/*").hasRole("TEACHER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/activities/*/participants").hasRole("TEACHER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/me/assignments").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.POST, "/api/v1/assignments/*/submission").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/activities/*/enrollment").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/activities/*/enrollment").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.GET, "/api/v1/activities", "/api/v1/activities/*").hasAnyRole("STUDENT", "TEACHER")
                        .requestMatchers("/api/v1/**").authenticated().anyRequest().permitAll())
                .addFilterAfter(concurrent, SecurityContextHolderFilter.class)
                .addFilterAfter(new PasswordChangeFilter(current, problems), AnonymousAuthenticationFilter.class);
        return http.build();
    }
}

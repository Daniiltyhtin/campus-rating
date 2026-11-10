package ru.campusrating.identity.internal;

import jakarta.validation.Validator;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import ru.campusrating.identity.api.AuthDtos.LoginRequest;

@Component
@Profile("prod")
class BootstrapAdmin implements ApplicationRunner {
    private final IdentityRepository repository;
    private final RegistrationService registration;
    private final Environment environment;
    private final Validator validator;
    BootstrapAdmin(IdentityRepository repository, RegistrationService registration, Environment environment, Validator validator) {
        this.repository = repository; this.registration = registration; this.environment = environment; this.validator = validator;
    }
    @Override public void run(ApplicationArguments arguments) {
        if (repository.hasAdmin()) return;
        String email = environment.getProperty("CAMPUS_BOOTSTRAP_ADMIN_EMAIL");
        String password = environment.getProperty("CAMPUS_BOOTSTRAP_ADMIN_PASSWORD");
        if (!validator.validate(new LoginRequest(email, password)).isEmpty()) {
            throw new IllegalStateException("Set valid CAMPUS_BOOTSTRAP_ADMIN_EMAIL and CAMPUS_BOOTSTRAP_ADMIN_PASSWORD for the first administrator");
        }
        registration.bootstrap(email, password);
    }
}

package ru.campusrating.identity.internal;

import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.identity.Role;

@Component
@Profile("demo")
// Не ApplicationRunner: пароли README должны перестать работать до того, как Tomcat примет запросы.
class DemoPasswords implements SmartInitializingSingleton {
    static final List<Long> DEMO_ACCOUNTS = List.of(1001L, 1002L, 1003L, 1004L,
            11001L, 11002L, 11003L, 11004L, 11005L, 11006L, 2001L, 2002L, 3001L);
    private final IdentityRepository repository;
    private final Passwords passwords;
    private final Environment environment;
    DemoPasswords(IdentityRepository repository, Passwords passwords, Environment environment) {
        this.repository = repository; this.passwords = passwords; this.environment = environment;
    }
    @Override @Transactional public void afterSingletonsInstantiated() {
        String prefix = environment.getProperty("DEMO_PASSWORD_PREFIX", "");
        if (prefix.isEmpty()) return;
        for (Role role : Role.values()) {
            String password = prefix + "-" + role.name().toLowerCase(Locale.ROOT) + "-1";
            repository.replacePasswords(DEMO_ACCOUNTS, role, passwords.hash(password, "DEMO_PASSWORD_PREFIX"));
        }
    }
}

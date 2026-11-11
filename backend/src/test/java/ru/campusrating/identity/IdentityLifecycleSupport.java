package ru.campusrating.identity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.campusrating.CampusRatingApplication;

final class IdentityLifecycleSupport {
    private IdentityLifecycleSupport() {}

    static ConfigurableApplicationContext start(PostgreSQLContainer database, String profile, Map<String, Object> overrides) {
        var properties = new HashMap<String, Object>();
        properties.put("server.port", 0);
        properties.put("spring.datasource.url", database.getJdbcUrl());
        properties.put("spring.datasource.username", database.getUsername());
        properties.put("spring.datasource.password", database.getPassword());
        properties.put("EMAIL_VERIFICATION", "off");
        properties.put("spring.main.banner-mode", "off");
        try {
            properties.put("campus.storage.dir", Files.createTempDirectory("identity-lifecycle-storage-").toString());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        properties.putAll(overrides);
        // Defaults не перекрывают application.yml; тестовый datasource и явный режим почты должны иметь приоритет.
        var arguments = properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
        return new SpringApplicationBuilder(CampusRatingApplication.class).profiles(profile).run(arguments);
    }

    static IdentityAcceptanceSupport.Browser browser(ConfigurableApplicationContext context) {
        return new IdentityAcceptanceSupport.Browser(context.getEnvironment().getRequiredProperty("local.server.port", Integer.class));
    }
}

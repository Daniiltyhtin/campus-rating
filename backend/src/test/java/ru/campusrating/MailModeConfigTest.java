package ru.campusrating;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import ru.campusrating.shared.mail.MailModeProperties;

import static org.assertj.core.api.Assertions.assertThat;

class MailModeConfigTest {
    private static final String MODE = "campus.mail.email-verification";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources().remove("systemEnvironment");
                context.getEnvironment().getPropertySources().remove("systemProperties");
            })
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(MailConfiguration.class)
            .withPropertyValues("spring.profiles.active=prod");

    @Test
    void missingModeStopsStartupAndNamesProperty() {
        runner.run(context -> assertThat(context.getStartupFailure())
                .isNotNull().hasStackTraceContaining(MODE));
    }

    @Test
    void unknownModeStopsStartupAndNamesProperty() {
        runner.withPropertyValues(MODE + "=maybe").run(context ->
                assertThat(context.getStartupFailure()).isNotNull().hasStackTraceContaining(MODE));
    }

    @Test
    void requiredWithoutSmtpStopsStartupAndNamesHost() {
        runner.withPropertyValues(MODE + "=required").run(context ->
                assertThat(context.getStartupFailure()).isNotNull()
                        .hasStackTraceContaining("spring.mail.host"));
    }

    @Test
    void requiredWithSmtpStopsStartupUntilMasterU15() {
        runner.withPropertyValues(MODE + "=required", "spring.mail.host=smtp.example.org")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull()
                        .hasStackTraceContaining(MODE).hasStackTraceContaining("U15"));
    }

    @Test
    void offStartsInDemo() {
        runner.withPropertyValues("spring.profiles.active=demo", MODE + "=off").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MailModeProperties.class);
            assertThat(context.getBean(MailModeProperties.class).emailVerification())
                    .isEqualTo(MailModeProperties.EmailVerification.OFF);
        });
    }

    @Test
    void offStartsInProd() {
        runner.withPropertyValues(MODE + "=off").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MailModeProperties.class);
            assertThat(context.getBean(MailModeProperties.class).emailVerification())
                    .isEqualTo(MailModeProperties.EmailVerification.OFF);
        });
    }

    @Test
    void actualYamlLimitsDestructiveLiquibaseSettingsToDemo() {
        runner.withPropertyValues("spring.profiles.active=demo", MODE + "=off").run(context -> {
            assertThat(context).hasNotFailed();
            var liquibase = context.getBean(LiquibaseProperties.class);
            assertThat(liquibase.getContexts()).containsExactly("default", "demo");
            assertThat(liquibase.isDropFirst()).isTrue();
        });
        runner.withPropertyValues(MODE + "=off").run(context -> {
            assertThat(context).hasNotFailed();
            var liquibase = context.getBean(LiquibaseProperties.class);
            assertThat(liquibase.getContexts()).containsExactly("default");
            assertThat(liquibase.isDropFirst()).isFalse();
        });
    }

    @Test
    void environmentVariableSelectsOffThroughActualYaml() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("mailTestEnvironment",
                                Map.of("EMAIL_VERIFICATION", "off"))))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MailModeProperties.class);
                    assertThat(context.getEnvironment().getProperty(MODE)).isEqualTo("off");
                    assertThat(context.getBean(MailModeProperties.class).emailVerification())
                            .isEqualTo(MailModeProperties.EmailVerification.OFF);
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ConfigurationPropertiesScan("ru.campusrating.shared.mail")
    @ComponentScan("ru.campusrating.shared.mail")
    @EnableConfigurationProperties(LiquibaseProperties.class)
    static class MailConfiguration {
    }
}

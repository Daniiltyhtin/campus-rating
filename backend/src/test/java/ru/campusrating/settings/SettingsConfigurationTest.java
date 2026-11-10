package ru.campusrating.settings;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import ru.campusrating.settings.internal.SettingsDefaults;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ConfigurationUnderTest.class);

    @Test
    void applicationYamlProvidesDefaultsAndDisablesModulithSchemaInitialization() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var defaults = context.getBean(SettingsDefaults.class);
            assertThat(defaults.penalty().defaultStep()).isEqualTo(20);
            assertThat(defaults.penalty().defaultInterval()).isEqualTo(Duration.ofMinutes(1));
            assertThat(defaults.penalty().pollPeriod()).isEqualTo(Duration.ofSeconds(1));
            assertThat(defaults.semester().accrual()).isEqualTo(200);
            assertThat(defaults.budget().monthlyLimit()).isEqualTo(1000);
            assertThat(context.getEnvironment().getProperty("spring.liquibase.contexts")).isEqualTo("default");
            assertThat(context.getEnvironment().getProperty(
                    "spring.modulith.events.jdbc.schema-initialization.enabled", Boolean.class)).isFalse();
        });
    }

    @Test
    void environmentVariablesOverrideDefaults() {
        runner.withPropertyValues("CAMPUS_PENALTY_DEFAULT_STEP=15", "CAMPUS_PENALTY_DEFAULT_INTERVAL=4s",
                "CAMPUS_PENALTY_POLL_PERIOD=500ms", "CAMPUS_SEMESTER_ACCRUAL=125",
                "CAMPUS_BUDGET_MONTHLY_LIMIT=750").run(context -> {
                    assertThat(context).hasNotFailed();
                    var defaults = context.getBean(SettingsDefaults.class);
                    assertThat(defaults.penalty().defaultStep()).isEqualTo(15);
                    assertThat(defaults.penalty().defaultInterval()).isEqualTo(Duration.ofSeconds(4));
                    assertThat(defaults.penalty().pollPeriod()).isEqualTo(Duration.ofMillis(500));
                    assertThat(defaults.semester().accrual()).isEqualTo(125);
                    assertThat(defaults.budget().monthlyLimit()).isEqualTo(750);
                });
    }

    @Test
    void demoInheritsDefaultsAndSchemaOwnership() {
        runner.withPropertyValues("spring.profiles.active=demo").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SettingsDefaults.class).semester().accrual()).isEqualTo(200);
            assertThat(context.getEnvironment().getProperty("spring.liquibase.contexts")).isEqualTo("default,demo");
            assertThat(context.getEnvironment().getProperty("spring.liquibase.drop-first", Boolean.class)).isTrue();
            assertThat(context.getEnvironment().getProperty(
                    "spring.modulith.events.jdbc.schema-initialization.enabled", Boolean.class)).isFalse();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SettingsDefaults.class)
    static class ConfigurationUnderTest {}
}

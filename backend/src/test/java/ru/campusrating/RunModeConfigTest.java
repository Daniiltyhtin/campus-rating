package ru.campusrating;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RunModeConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources().remove("systemEnvironment");
                context.getEnvironment().getPropertySources().remove("systemProperties");
            })
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @ParameterizedTest
    @CsvSource({"prod,false,default", "demo,true,'default,demo'"})
    void profileControlsDemoDataAndRecreation(String profile, boolean dropFirst, String contexts) {
        runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.liquibase.drop-first", Boolean.class)).isEqualTo(dropFirst);
            assertThat(environment.getProperty("spring.liquibase.contexts")).isEqualTo(contexts);
        });
    }

    @ParameterizedTest
    @CsvSource({"campus.demo.reset=false,false", "campus.demo.reset=true,true", "CAMPUS_DEMO_RESET=false,false"})
    void demoResetFlagControlsRecreation(String setting, boolean dropFirst) {
        runner.withPropertyValues("spring.profiles.active=demo", setting).run(context -> {
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.liquibase.drop-first", Boolean.class)).isEqualTo(dropFirst);
            assertThat(environment.getProperty("spring.liquibase.contexts")).isEqualTo("default,demo");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "demo"})
    void smtpCallsHaveTimeouts(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile, "spring.mail.properties.mail.smtp.ssl.enable=true")
                .run(context -> {
                    var properties = Binder.get(context.getEnvironment())
                            .bind("spring.mail.properties", Bindable.mapOf(String.class, String.class)).get();
                    assertThat(properties)
                            .containsEntry("mail.smtp.connectiontimeout", "10000")
                            .containsEntry("mail.smtp.timeout", "10000")
                            .containsEntry("mail.smtp.writetimeout", "10000")
                            .containsEntry("mail.smtp.ssl.enable", "true");
                });
    }
}

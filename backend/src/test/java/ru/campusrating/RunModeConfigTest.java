package ru.campusrating;

import org.junit.jupiter.params.ParameterizedTest;
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
}

package ru.campusrating.shared.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;

import static org.assertj.core.api.Assertions.assertThat;

class PublicUrlConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources().remove("systemEnvironment");
                context.getEnvironment().getPropertySources().remove("systemProperties");
            })
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(MailConfiguration.class)
            .withPropertyValues("spring.profiles.active=prod", "campus.mail.email-verification=off");

    @ParameterizedTest
    @ValueSource(strings = {"https://", "https:///path", "ftp://campus.example.ru", "campus.example.ru", "/campus",
            "https://campus.example.ru/?next=1", "https://campus.example.ru/#top", "", " "})
    void invalidPublicUrlStopsStartupAndNamesProperty(String value) {
        runner.withPropertyValues("campus.public-url=" + value).run(context -> assertThat(context.getStartupFailure())
                .isNotNull()
                .hasStackTraceContaining("campus.public-url must be an absolute http or https URL"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://campus.example.ru", "http://localhost:8080", "https://campus.example.ru/rating/"})
    void validPublicUrlStarts(String value) {
        runner.withPropertyValues("campus.public-url=" + value)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(PublicUrlProperties.class));
    }

    @Test
    void defaultPublicUrlStarts() {
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(PublicUrlProperties.class));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ConfigurationPropertiesScan("ru.campusrating.shared.mail")
    @ComponentScan("ru.campusrating.shared.mail")
    static class MailConfiguration {
    }
}

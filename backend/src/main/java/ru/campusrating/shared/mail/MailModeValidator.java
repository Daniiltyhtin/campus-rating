package ru.campusrating.shared.mail;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MailModeValidator {
    public MailModeValidator(MailModeProperties properties, Environment environment) {
        if (properties.emailVerification() == MailModeProperties.EmailVerification.REQUIRED
                && !StringUtils.hasText(environment.getProperty("spring.mail.host"))) {
            throw new IllegalStateException(
                    "spring.mail.host must be set when campus.mail.email-verification=required");
        }
        // Master-U15 removes this guard once email verification is implemented.
        if (properties.emailVerification() == MailModeProperties.EmailVerification.REQUIRED) {
            throw new IllegalStateException(
                    "campus.mail.email-verification=required is unavailable until master-U15");
        }
    }
}

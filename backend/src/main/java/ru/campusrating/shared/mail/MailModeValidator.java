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
    }
}

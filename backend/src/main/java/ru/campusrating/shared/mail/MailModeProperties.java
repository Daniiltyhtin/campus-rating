package ru.campusrating.shared.mail;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("campus.mail")
public record MailModeProperties(
        @NotNull(message = "campus.mail.email-verification must be required or off")
        EmailVerification emailVerification) {
    public enum EmailVerification {
        REQUIRED, OFF
    }
}

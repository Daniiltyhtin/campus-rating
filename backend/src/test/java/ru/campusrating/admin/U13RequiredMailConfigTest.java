package ru.campusrating.admin;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.campusrating.shared.mail.MailModeProperties;
import ru.campusrating.shared.mail.MailModeValidator;
import static org.assertj.core.api.Assertions.assertThat;

class U13RequiredMailConfigTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withBean(MailModeProperties.class,()->new MailModeProperties(MailModeProperties.EmailVerification.REQUIRED))
            .withUserConfiguration(MailModeValidator.class);
    @Test void requiredWithoutSmtpStillRejectsStartup() {
        runner.withPropertyValues("spring.mail.host=").run(context->assertThat(context.getStartupFailure()).isNotNull().hasStackTraceContaining("spring.mail.host"));
    }
    @Test void requiredWithSmtpIsAvailableForU13() {
        runner.withPropertyValues("spring.mail.host=127.0.0.1").run(context->assertThat(context).hasNotFailed());
    }
}

package ru.campusrating.admin;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import ru.campusrating.support.MutableClock;
import ru.campusrating.support.U13MailTestSupport;
import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties="campus.mail.email-verification=required")
class EmailVerificationResendLimitIT extends U13MailTestSupport {
    @Autowired MutableClock clock;

    private long sentTo(String address) {
        return messages.stream().filter(m->m.getTo()!=null&&List.of(m.getTo()).contains(address)).count();
    }

    private void resend(String address) {
        request(browser(),"POST","/api/v1/auth/email/verification",Map.of("email",address),204);
    }

    @Test void resendIsLimitedToOncePerMinuteAndFivePerDayWithNeutralAnswer() {
        student("limited",faculty,group);
        String address=email("limited");
        long afterRegistration=sentTo(address);
        assertThat(afterRegistration).isEqualTo(1);
        resend(address);
        assertThat(sentTo(address)).isEqualTo(1);
        for(int i=0;i<10;i++) { clock.advance(Duration.ofMinutes(2)); resend(address); }
        assertThat(sentTo(address)).isEqualTo(5);
        clock.advance(Duration.ofHours(25));
        resend(address);
        assertThat(sentTo(address)).isEqualTo(6);
    }
}

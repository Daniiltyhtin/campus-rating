package ru.campusrating.admin;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import ru.campusrating.support.U13MailTestSupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

class U13AdminMailIT extends U13MailTestSupport {
    @Test void r7r8OffStillSendsSetupLinkAndSuccessfulSetIsOneUse() {
        imported("setup"); String token=token(email("setup"),"/password/set/");
        invalid("POST","/api/v1/auth/password/set",Map.of("token",token,"password","x"),"password");
        request(browser(),"POST","/api/v1/auth/password/set",Map.of("token",token,"password","Synthetic-set-password-55"),204);
        request(browser(),"POST","/api/v1/auth/login",Map.of("email",email("setup"),"password","Synthetic-set-password-55"),200);
        var used=request(browser(),"POST","/api/v1/auth/password/set",Map.of("token",token,"password","Synthetic-another-password-56"),409);
        assertThat(used.path("detail").asText()).isEqualTo("Ссылка уже использована");
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().getFrom()).isEqualTo("sender@example.test");
    }
    @Test void r8ExpiredLinkCannotSetPasswordAfterTwentyFiveHours() {
        imported("expired"); String token=token(email("expired"),"/password/set/");
        clock.set(clock.instant().plusSeconds(25*3600));
        var result=request(browser(),"POST","/api/v1/auth/password/set",Map.of("token",token,"password",PASSWORD),409);
        assertThat(result.path("detail").asText()).isEqualTo("Ссылка устарела. Попросите преподавателя или администратора выставить временный пароль");
        request(browser(),"POST","/api/v1/auth/login",Map.of("email",email("expired"),"password",PASSWORD),401);
    }
    @Test void r8TemporaryPasswordInvalidatesUnusedSetupLink() {
        long id=imported("invalidated"); String token=token(email("invalidated"),"/password/set/");
        String temporary=request(admin,"POST","/api/v1/users/"+id+"/temporary-password",null,200).path("password").asText();
        var result=request(browser(),"POST","/api/v1/auth/password/set",Map.of("token",token,"password",PASSWORD),409);
        assertThat(result.path("detail").asText()).isEqualTo("Ссылка уже использована");
        request(browser(),"POST","/api/v1/auth/login",Map.of("email",email("invalidated"),"password",temporary),200);
    }
    @Test void r7SmtpFailureDoesNotUndoCommittedStudentOrChangeReport() {
        doThrow(new MailSendException("Synthetic delivery failure")).when(sender).send(any(SimpleMailMessage.class));
        long id=imported("deliveryfailed");
        assertThat(items(get("/api/v1/admin/users?search="+prefix+"deliveryfailed"))).hasSize(1);
        assertThat(jdbc.queryForObject("select sum(amount) from ledger_entry where student_id=? and kind='INITIAL'",Integer.class,id)).isEqualTo(1000);
    }
}

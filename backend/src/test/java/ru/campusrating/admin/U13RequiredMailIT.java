package ru.campusrating.admin;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import ru.campusrating.support.U13MailTestSupport;
import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties="campus.mail.email-verification=required")
class U13RequiredMailIT extends U13MailTestSupport {
    @BeforeEach void verifyFixtureAdministrator() {
        jdbc.update("update app_user set email_verified=true where email=?",email("admin"));
        assertThat(get("/api/v1/auth/config").path("emailVerification").asText()).isEqualTo("REQUIRED");
    }
    private String reset(long user) { return request(admin,"POST","/api/v1/users/"+user+"/temporary-password",null,200).path("password").asText(); }
    @Test void r31r49TemporaryPasswordDoesNotVerifyEmailAndOnlyGrantsRestrictedSession() {
        long target=student("unverified",faculty,group);
        problem(browser(),"POST","/api/v1/auth/login",Map.of("email",email("unverified"),"password",PASSWORD),403,"EMAIL_NOT_VERIFIED");
        String temporary=reset(target); var session=browser();
        assertThat(request(session,"POST","/api/v1/auth/login",Map.of("email",email("unverified"),"password",temporary),200).path("mustChangePassword").asBoolean()).isTrue();
        var status=get(session,"/api/v1/auth/email/status");
        assertThat(status.path("required").asBoolean()).isTrue(); assertThat(status.path("verified").asBoolean()).isFalse();
        problem(session,"GET","/api/v1/me/assignments?view=active",null,403,"PASSWORD_CHANGE_REQUIRED");
        request(session,"POST","/api/v1/auth/password",Map.of("currentPassword",temporary,"newPassword","New-synthetic-password-91"),204);
        problem(session,"GET","/api/v1/me/assignments?view=active",null,403,"EMAIL_NOT_VERIFIED");
        request(browser(),"POST","/api/v1/auth/email/verification",Map.of("email",email("unverified")),204);
        String token=token(email("unverified"),"/email/confirm/");
        request(browser(),"POST","/api/v1/auth/email/confirmation",Map.of("token",token),204);
        get(session,"/api/v1/me/assignments?view=active");
        assertThat(get(session,"/api/v1/auth/email/status").path("verified").asBoolean()).isTrue();
    }
    @Test void r8SetupLinkVerifiesEmailAndLaterResetPreservesConfirmation() {
        long target=imported("setupverified");
        String token=token(email("setupverified"),"/password/set/");
        request(browser(),"POST","/api/v1/auth/password/set",Map.of("token",token,"password",PASSWORD),204);
        var original=login("setupverified");
        assertThat(get(original,"/api/v1/auth/email/status").path("verified").asBoolean()).isTrue();
        String temporary=reset(target); var session=browser();
        request(session,"POST","/api/v1/auth/login",Map.of("email",email("setupverified"),"password",temporary),200);
        assertThat(get(session,"/api/v1/auth/email/status").path("verified").asBoolean()).isTrue();
        request(session,"POST","/api/v1/auth/password",Map.of("currentPassword",temporary,"newPassword","New-synthetic-password-92"),204);
        get(session,"/api/v1/me/assignments?view=active");
    }
    @Test void r8TokensCannotBeUsedForAnotherPurpose() {
        imported("purpose"); String setup=token(email("purpose"),"/password/set/");
        request(browser(),"POST","/api/v1/auth/email/confirmation",Map.of("token",setup),409);
        request(browser(),"POST","/api/v1/auth/password/set",Map.of("token",setup,"password",PASSWORD),204);
        var session=login("purpose");
        assertThat(get(session,"/api/v1/auth/email/status").path("verified").asBoolean()).isTrue();
        student("verificationonly",faculty,group);
        String confirmation=token(email("verificationonly"),"/email/confirm/");
        request(browser(),"POST","/api/v1/auth/password/set",Map.of("token",confirmation,"password",PASSWORD),409);
        request(browser(),"POST","/api/v1/auth/email/confirmation",Map.of("token",confirmation),204);
        login("verificationonly");
    }
    @Test void r31VerificationRequestIsNeutralForUnknownAndAlreadyVerifiedAddresses() {
        request(browser(),"POST","/api/v1/auth/email/verification",Map.of("email",email("unknown")),204);
        request(browser(),"POST","/api/v1/auth/email/verification",Map.of("email",email("admin")),204);
        assertThat(messages).isEmpty();
    }
}

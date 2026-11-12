package ru.campusrating.identity.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.internal.PasswordSetupService;

@RestController
public class SetPasswordController {
    private final PasswordSetupService service;private final CurrentUser current;
    public SetPasswordController(PasswordSetupService service,CurrentUser current) {this.service=service;this.current=current;}
    @PostMapping("/api/v1/auth/password/set") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setImportedStudentPassword(@Valid @RequestBody PasswordSetupRequest body) {service.setPassword(body.token(),body.password());}
    @PostMapping("/api/v1/auth/email/verification") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestEmailVerification(@Valid @RequestBody EmailVerificationRequest body) {service.resend(body.email());}
    @PostMapping("/api/v1/auth/email/confirmation") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmEmail(@Valid @RequestBody EmailConfirmationRequest body) {service.confirm(body.token());}
    @GetMapping("/api/v1/auth/email/status") public EmailVerificationStatus getEmailVerificationStatus() {return new EmailVerificationStatus(service.required(),service.verified(current.require().id()));}
    @Schema(requiredProperties={"token","password"}) public record PasswordSetupRequest(@NotBlank String token,@NotBlank @Size(min=8,max=72) String password) {}
    @Schema(requiredProperties="email") public record EmailVerificationRequest(@NotBlank @Email String email) {}
    @Schema(requiredProperties="token") public record EmailConfirmationRequest(@NotBlank String token) {}
    @Schema(requiredProperties={"required","verified"}) public record EmailVerificationStatus(boolean required,boolean verified) {}
}

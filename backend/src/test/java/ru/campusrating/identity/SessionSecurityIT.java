package ru.campusrating.identity;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionSecurityIT extends IdentityAcceptanceSupport {
    @Test
    void changingRequestsRequireCsrfIncludingRegistrationAndLogin() {
        var guest = browser();
        assertThat(guest.get("/api/v1/auth/csrf").status()).isEqualTo(204);
        assertProblem(guest.postWithoutCsrf("/api/v1/auth/register/student", studentBody(email("student"), faculty, group)), 403, "CSRF_INVALID");
        registerStudent(guest, "student");
        assertProblem(guest.postWithoutCsrf("/api/v1/auth/login", Map.of("email", email("student"), "password", PASSWORD)), 403, "CSRF_INVALID");
        login(guest, email("student"));
        assertThat(guest.get("/api/v1/auth/me").status()).isEqualTo(200);
        assertThat(guest.cookies()).anySatisfy(cookie -> {
            assertThat(cookie.getName()).isEqualTo("JSESSIONID");
            assertThat(cookie.isHttpOnly()).isTrue();
        });
    }

    @Test
    void loginAndLogoutUseActualCookieSession() {
        var browser = browser();
        long id = registerStudent(browser, "student");
        String beforeLoginCsrf = csrfValue(browser);
        login(browser, email("student"));
        assertThat(browser.get("/api/v1/auth/csrf").status()).isEqualTo(204);
        String afterLoginCsrf = csrfValue(browser);
        assertThat(afterLoginCsrf).isNotEqualTo(beforeLoginCsrf);
        assertThat(browser.get("/api/v1/auth/me").body().path("id").asLong()).isEqualTo(id);
        String oldSession = browser.cookies().stream().filter(cookie -> cookie.getName().equals("JSESSIONID"))
                .findFirst().orElseThrow().getValue();
        assertThat(browser.post("/api/v1/auth/logout", Map.of()).status()).isEqualTo(204);
        assertProblem(browser.get("/api/v1/auth/me"), 401, "UNAUTHENTICATED");
        assertProblem(browser().getWithCookie("/api/v1/auth/me", "JSESSIONID=" + oldSession), 401, "UNAUTHENTICATED");
        assertThat(browser.get("/api/v1/auth/csrf").status()).isEqualTo(204);
        assertThat(csrfValue(browser)).isNotEqualTo(afterLoginCsrf);
    }

    @Test
    void studentCannotCreateTeacherCheckpointWithValidCsrf() {
        var student = browser();
        registerStudent(student, "student");
        login(student, email("student"));
        assertProblem(student.post("/api/v1/checkpoints", Map.of()), 403, "FORBIDDEN");
    }

    @Test
    void adminReconciliationRequiresAuthenticationAndAdminRole() {
        assertProblem(browser().get("/api/v1/admin/ledger/reconciliation"), 401, "UNAUTHENTICATED");
        var student = browser();
        registerStudent(student, "student");
        login(student, email("student"));
        assertProblem(student.get("/api/v1/admin/ledger/reconciliation"), 403, "FORBIDDEN");
        var teacher = browser();
        registerTeacher(teacher, "teacher");
        login(teacher, email("teacher"));
        assertProblem(teacher.get("/api/v1/admin/ledger/reconciliation"), 403, "FORBIDDEN");
        var admin = browser();
        long id = registerStudent(admin, "admin");
        jdbc.update("delete from group_membership where student_id=?", id);
        jdbc.update("update app_user set role='ADMIN' where id=?", id);
        login(admin, email("admin"));
        assertThat(admin.get("/api/v1/admin/ledger/reconciliation").status()).isEqualTo(200);
    }

    @Test
    void passwordChangeRejectsReuseAndInvalidatesOtherSessionOnly() {
        var first = browser();
        long id = registerStudent(first, "student");
        var second = browser();
        login(first, email("student"));
        login(second, email("student"));
        jdbc.update("update app_user set must_change_password=true where id=?", id);
        assertThat(first.get("/api/v1/auth/csrf").status()).isEqualTo(204);
        String beforeChangeCsrf = csrfValue(first);
        assertThat(first.get("/api/v1/auth/me").body().path("mustChangePassword").asBoolean()).isTrue();
        assertProblem(first.get("/api/v1/directory/faculties"), 403, "PASSWORD_CHANGE_REQUIRED");
        assertProblem(first.post("/api/v1/auth/password", Map.of("currentPassword", PASSWORD, "newPassword", PASSWORD)), 400, "PASSWORD_REUSED");
        var mismatch = first.post("/api/v1/auth/password", Map.of("currentPassword", "Wrong-password-1", "newPassword", "Changed-password-43"));
        assertProblem(mismatch, 400, "VALIDATION_FAILED");
        assertThat(mismatch.body().path("fieldErrors").toString()).contains("currentPassword", "MISMATCH");
        var change = first.post("/api/v1/auth/password", Map.of("currentPassword", PASSWORD, "newPassword", "Changed-password-43"));
        assertThat(change.status()).isEqualTo(204);
        assertThat(first.get("/api/v1/auth/csrf").status()).isEqualTo(204);
        assertThat(csrfValue(first)).isNotEqualTo(beforeChangeCsrf);
        assertThat(first.get("/api/v1/auth/me").status()).isEqualTo(200);
        assertThat(first.get("/api/v1/auth/me").body().path("mustChangePassword").asBoolean()).isFalse();
        assertProblem(second.get("/api/v1/auth/me"), 401, "UNAUTHENTICATED");
        assertProblem(browser().post("/api/v1/auth/login", Map.of("email", email("student"), "password", PASSWORD)), 401, "INVALID_CREDENTIALS");
        assertThat(browser().post("/api/v1/auth/login", Map.of("email", email("student"), "password", "Changed-password-43")).status()).isEqualTo(200);
    }

    @Test
    void publicConfigReflectsExplicitVerificationMode() {
        var config = browser().get("/api/v1/auth/config");
        assertThat(config.status()).isEqualTo(200);
        assertThat(config.body().has("demo")).isTrue();
        assertThat(config.body().path("demo").asBoolean()).isFalse();
        assertThat(config.body().path("emailVerification").asText()).isEqualTo("OFF");
    }

    private String csrfValue(Browser browser) {
        return browser.cookies().stream().filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .findFirst().orElseThrow(() -> new AssertionError("CSRF cookie is missing")).getValue();
    }
}

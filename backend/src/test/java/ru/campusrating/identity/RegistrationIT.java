package ru.campusrating.identity;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import(MembershipEventProbe.class)
class RegistrationIT extends IdentityAcceptanceSupport {
    @Autowired private MembershipEventProbe.Listener membershipEvents;

    @Test
    void studentGetsPendingMembershipAndExactlyOneInitialPostingWithoutSessionOrEvent() {
        var browser = browser();
        long id = registerStudent(browser, "student");
        assertThat(ledger.balances(id).ar()).isEqualTo(1000);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where student_id=? and kind='INITIAL' and amount=1000",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state from group_membership where student_id=?", String.class, id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select activated_at from group_membership where student_id=?", java.sql.Timestamp.class, id)).isNull();
        var hash = jdbc.queryForObject("select password_hash from app_user where id=?", String.class, id);
        assertThat(hash).startsWith("$2").isNotEqualTo(PASSWORD);
        assertProblem(browser.get("/api/v1/auth/me"), 401, "UNAUTHENTICATED");
        await().during(java.time.Duration.ofSeconds(1)).atMost(java.time.Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(membershipEvents.committed()).noneMatch(event -> event.studentId() == id));
    }

    @Test
    void approvalOfRegisteredStudentActivatesMembershipAndPublishesEventOnce() {
        long id = registerStudent(browser(), "student");
        registerTeacher(browser(), "teacher");
        jdbc.update("insert into teacher_approval(teacher_id, approved_at) select id, now() from app_user where email=?", email("teacher"));
        var teacher = browser();
        login(teacher, email("teacher"));
        clock.advance(java.time.Duration.ofMinutes(5));
        assertThat(teacher.post("/api/v1/memberships/" + id + "/approval", Map.of()).status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("select state from group_membership where student_id=?", String.class, id)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select activated_at from group_membership where student_id=?", java.sql.Timestamp.class, id)
                .toInstant()).isEqualTo(clock.instant());
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(membershipEvents.committed()).filteredOn(event -> event.studentId() == id)
                        .containsExactly(new MembershipActivated(id, group, clock.instant())));
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where student_id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void duplicateEmailIsCaseInsensitiveAndDoesNotAccrueAgain() {
        var browser = browser();
        long id = registerStudent(browser, "student");
        assertProblem(browser.post("/api/v1/auth/register/student", studentBody(email("student").toUpperCase(java.util.Locale.ROOT), faculty, group)),
                409, "EMAIL_TAKEN");
        assertThat(jdbc.queryForObject("select count(*) from app_user where lower(email)=lower(?)", Integer.class, email("student"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where student_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(ledger.balances(id).ar()).isEqualTo(1000);
    }

    @Test
    void groupFromDifferentFacultyIsRejectedBeforeAnyWrites() {
        var reply = browser().post("/api/v1/auth/register/student", studentBody(email("wrong-group"), faculty, otherGroup));
        assertProblem(reply, 400, "VALIDATION_FAILED");
        assertThat(reply.body().path("fieldErrors").get(0).path("field").asText()).isEqualTo("groupId");
        assertThat(reply.body().path("fieldErrors").get(0).path("code").asText()).isEqualTo("MISMATCH");
        assertThat(jdbc.queryForObject("select count(*) from app_user where email=?", Integer.class, email("wrong-group"))).isZero();
    }

    @Test
    void takenEmailIsReportedBeforeGroupValidation() {
        var browser = browser();
        registerStudent(browser, "taken");
        assertProblem(browser.post("/api/v1/auth/register/student", studentBody(email("taken"), faculty, otherGroup)),
                409, "EMAIL_TAKEN");
    }

    @Test
    void teacherAllowlistIsCheckedBeforeEmailTakenAndIgnoresCase() {
        jdbc.update("insert into teacher_allowlist(email,department_id) values (?,?)", email("reserved"), department);
        registerTeacher(browser(), "reserved");
        assertProblem(browser().post("/api/v1/auth/register/student", studentBody(email("reserved").toUpperCase(java.util.Locale.ROOT), faculty, group)),
                409, "EMAIL_RESERVED_FOR_TEACHER");
    }

    @Test
    void utf8PasswordBeyondBcryptLimitIsValidationError() {
        var request = new java.util.HashMap<>(studentBody(email("long-password"), faculty, group));
        request.put("password", "я".repeat(37));
        var response = browser().post("/api/v1/auth/register/student", request);
        assertProblem(response, 400, "VALIDATION_FAILED");
        assertThat(response.body().path("fieldErrors").toString()).contains("password", "TOO_LONG");
    }

    @Test
    void unknownEmailAndWrongPasswordHaveSameDomainError() {
        registerStudent(browser(), "student");
        assertProblem(browser().post("/api/v1/auth/login", Map.of("email", email("absent"), "password", PASSWORD)), 401, "INVALID_CREDENTIALS");
        assertProblem(browser().post("/api/v1/auth/login", Map.of("email", email("student"), "password", "Wrong-password-1")), 401, "INVALID_CREDENTIALS");
    }

    @Test
    void legacyUserWithoutHashDoesNotHaveAnImplicitPassword() {
        jdbc.update("insert into app_user(email,role,full_name) values (?,'STUDENT','Техническая строка')", email("legacy"));
        assertProblem(browser().post("/api/v1/auth/login", Map.of("email", email("legacy"), "password", PASSWORD)), 401, "INVALID_CREDENTIALS");
    }
}

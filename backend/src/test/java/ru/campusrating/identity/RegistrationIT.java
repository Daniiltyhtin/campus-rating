package ru.campusrating.identity;

import ru.campusrating.support.UserNameFixture;
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
        jdbc.update("insert into app_user(email,role,last_name,first_name,middle_name) values (?,'STUDENT',?,?,?)",
                email("legacy"),
                UserNameFixture.last("Техническая строка"),
                UserNameFixture.first("Техническая строка"),
                UserNameFixture.middle("Техническая строка"));
        assertProblem(browser().post("/api/v1/auth/login", Map.of("email", email("legacy"), "password", PASSWORD)), 401, "INVALID_CREDENTIALS");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "Covers AE1, AE4, AE41: {0}, middle={2}")
    @org.junit.jupiter.params.provider.CsvSource(value = {
        "Иванова|Мария|OMITTED|Иванова Мария",
        "Иванова|Мария|EMPTY|Иванова Мария",
        "Иванова|Мария|'   '|Иванова Мария",
        "'  Римский-Корсаков '|Николай|' '|Римский-Корсаков Николай",
        "де Голль|Шарль|EMPTY|де Голль Шарль"
    }, delimiter = '|')
    void registrationNormalizesOptionalMiddleName(String last, String first, String middle, String full) {
        var body = new java.util.HashMap<>(studentBody(email("names"), faculty, group));
        body.put("lastName", last); body.put("firstName", first);
        body.remove("middleName");
        if (!middle.equals("OMITTED")) body.put("middleName", middle.equals("EMPTY") ? "" : middle);
        var session = browser();
        var reply = session.post("/api/v1/auth/register/student", body);
        assertThat(reply.status()).isEqualTo(201);
        long id = reply.body().path("userId").asLong();
        assertThat(jdbc.queryForObject("select last_name from app_user where id=?", String.class, id)).isEqualTo(last.strip());
        assertThat(jdbc.queryForObject("select middle_name from app_user where id=?", String.class, id)).isNull();
        login(session, email("names"));
        var me = session.get("/api/v1/auth/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().path("fullName").asText()).isEqualTo(full);
        for (String field : java.util.List.of("lastName", "firstName", "middleName")) assertThat(me.body().has(field)).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "Covers AE2: {0} profile exposes composed full name only")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"student", "teacher"})
    void profileHasFullName(String role) {
        var body = new java.util.HashMap<>(studentBody(email("profile-name"), faculty, group));
        body.putAll(Map.of("lastName", "Петров", "firstName", "Павел", "middleName", "Петрович"));
        if (role.equals("teacher")) { body.remove("facultyId"); body.remove("groupId"); body.put("departmentId", department); }
        var session = browser();
        var created = session.post("/api/v1/auth/register/" + role, body);
        assertThat(created.status()).isEqualTo(201);
        login(session, email("profile-name"));
        var profile = session.get("/api/v1/users/" + created.body().path("userId").asLong() + "/profile");
        assertThat(profile.status()).isEqualTo(200);
        assertThat(profile.body().path("fullName").asText()).isEqualTo("Петров Павел Петрович");
        for (String field : java.util.List.of("lastName", "firstName", "middleName")) assertThat(profile.body().has(field)).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "Covers AE5: {0} rejects {1} for {2}")
    @org.junit.jupiter.params.provider.CsvSource({
        "lastName,long,student", "firstName,long,student", "middleName,long,student",
        "lastName,empty,student", "firstName,empty,student", "lastName,spaces,student", "firstName,spaces,student",
        "lastName,missing,student", "firstName,missing,student",
        "lastName,long,teacher", "firstName,long,teacher", "middleName,long,teacher",
        "lastName,empty,teacher", "firstName,empty,teacher", "lastName,spaces,teacher", "firstName,spaces,teacher",
        "lastName,missing,teacher", "firstName,missing,teacher"
    })
    void invalidNamePartsAreFieldErrorsWithoutWrites(String field, String kind, String role) {
        var body = new java.util.HashMap<>(studentBody(email("invalid-name"), faculty, group));
        if (role.equals("teacher")) { body.remove("facultyId"); body.remove("groupId"); body.put("departmentId", department); }
        body.put(field, switch (kind) { case "long" -> "Я".repeat(101); case "spaces" -> "   "; default -> ""; });
        if (kind.equals("missing")) body.remove(field);
        var response = browser().post("/api/v1/auth/register/" + role, body);
        assertProblem(response, 400, "VALIDATION_FAILED");
        assertThat(response.body().path("fieldErrors").valueStream().map(n -> n.path("field").asText()).toList()).contains(field);
        assertThat(jdbc.queryForObject("select count(*) from app_user where email=?", Integer.class, email("invalid-name"))).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "Covers AE4: teacher optional middle {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"OMITTED", "", "   "})
    void teacherUsesSameNormalization(String middle) {
        var body = new java.util.HashMap<String, Object>(Map.of("lastName", "  Римский-Корсаков ", "firstName", "Николай",
                "email", email("teacher-name"), "password", PASSWORD, "departmentId", department));
        if (!middle.equals("OMITTED")) body.put("middleName", middle);
        var session = browser();
        var result = session.post("/api/v1/auth/register/teacher", body);
        assertThat(result.status()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select middle_name from app_user where id=?", String.class, result.body().path("userId").asLong())).isNull();
        login(session, email("teacher-name"));
        assertThat(session.get("/api/v1/auth/me").body().path("fullName").asText()).isEqualTo("Римский-Корсаков Николай");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "Covers AE5: {0} accepts 100 characters in each part after trimming")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"student", "teacher"})
    void acceptsMaximumLength(String role) {
        var body = new java.util.HashMap<>(studentBody(email("boundary"), faculty, group));
        for (String field : java.util.List.of("lastName", "firstName", "middleName")) body.put(field, "  " + "Я".repeat(100) + "  ");
        if (role.equals("teacher")) { body.remove("facultyId"); body.remove("groupId"); body.put("departmentId", department); }
        assertThat(browser().post("/api/v1/auth/register/" + role, body).status()).isEqualTo(201);
    }
}

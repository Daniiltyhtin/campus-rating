package ru.campusrating.identity;

import ru.campusrating.support.UserNameFixture;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.campusrating.support.AdminHttpTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTeachersIT extends AdminHttpTestSupport {
    @ParameterizedTest
    @ValueSource(strings = {"guest", "student", "teacher", "temporaryAdmin"})
    void allEightOperationsEnforceAdministratorAndPasswordChange(String actor) {
        long target = teacher("target", "Target");
        Browser session = browser();
        if (actor.equals("student")) {
            student("actor", faculty, group);
            session = login("actor");
        } else if (actor.equals("teacher")) {
            teacher("actor", "Actor");
            session = login("actor");
        } else if (actor.equals("temporaryAdmin")) {
            session = administrator("temporary", false);
        }
        int status = actor.equals("guest") ? 401 : 403;
        String code = actor.equals("guest") ? "UNAUTHENTICATED"
                : actor.equals("temporaryAdmin") ? "PASSWORD_CHANGE_REQUIRED" : "FORBIDDEN";
        String path = teacherPath(target);
        var before = get(path);
        var grants = get(path + "/grants");
        for (String read : List.of(TEACHERS, path, path + "/grants", path + "/accessible-groups")) {
            problem(session, "GET", read, null, status, code);
        }
        problem(session, "POST", path + "/approval", Map.of("departmentId", department), status, code);
        problem(session, "DELETE", path + "/application", null, status, code);
        problem(session, "POST", path + "/grants", Map.of("kind", "GROUP", "groupId", otherGroup), status, code);
        problem(session, "DELETE", path + "/grants/" + ABSENT, null, status, code);
        assertThat(get(path)).isEqualTo(before);
        assertThat(get(path + "/grants")).isEqualTo(grants);
    }

    @Test
    void listContainsAllTeachersOrderedByNameThenIdAndPendingCountTracksApproval() {
        var before = items(get(TEACHERS));
        long z = teacher("z", "Zulu");
        long a1 = teacher("a1", "Alpha");
        long a2 = teacher("a2", "Alpha");
        long student = student("student", faculty, group);
        approve(z, department);
        var list = get(TEACHERS);
        sorted(list, "fullName");
        assertThat(ids(list)).containsExactlyInAnyOrderElementsOf(java.util.stream.Stream.concat(
                before.stream().map(n -> n.path("id").asLong()), java.util.stream.Stream.of(z, a1, a2)).toList());
        assertThat(ids(list)).doesNotContain(student);
        assertThat(ids(list).indexOf(a1)).isLessThan(ids(list).indexOf(a2));
        long pendingBefore = before.stream().filter(n -> !n.path("teacherApproved").asBoolean()).count();
        assertThat(items(list).stream().filter(n -> !n.path("teacherApproved").asBoolean()).count()).isEqualTo(pendingBefore + 2);
        for (long id : List.of(a1, a2)) {
            var card = get(teacherPath(id));
            assertThat(card.path("teacherApproved").asBoolean()).isFalse();
            assertThat(card.path("department").path("id").asLong()).isEqualTo(department);
            assertThat(items(list)).contains(card);
        }
    }

    @Test
    void approvalUsesConfirmedDepartmentAndIsIdempotent() {
        long id = teacher("teacher", "Teacher");
        var session = login("teacher");
        assertThat(scope(id)).isEmpty();
        var approved = approve(id, otherDepartment);
        assertThat(approved.path("teacherApproved").asBoolean()).isTrue();
        assertThat(approved.path("department").path("id").asLong()).isEqualTo(otherDepartment);
        assertThat(scope(id)).containsExactly(otherGroup, olderGroup);
        teacherScope(session, List.of(otherGroup, olderGroup));
        assertThat(approve(id, otherDepartment)).isEqualTo(approved);
        problem(admin, "POST", teacherPath(id) + "/approval", Map.of("departmentId", department), 409, "INVALID_STATE");
        assertThat(get(teacherPath(id))).isEqualTo(approved);
        assertThat(scope(id)).containsExactly(otherGroup, olderGroup);
    }

    @Test
    void nonexistentApprovalDepartmentDoesNotApproveOrChangeDepartment() {
        long id = teacher("teacher", "Teacher");
        var before = get(teacherPath(id));
        invalid("POST", teacherPath(id) + "/approval", Map.of("departmentId", ABSENT), "departmentId");
        assertThat(get(teacherPath(id))).isEqualTo(before);
        assertThat(scope(id)).isEmpty();
    }

    @Test
    void registeredTeacherCannotCreateCheckpointUntilAdministratorApproves() {
        long id = teacher("teacher", "Teacher");
        var session = login("teacher");
        assertThat(get(session, "/api/v1/auth/me").path("teacherApproved").asBoolean()).isFalse();
        assertThat(ids(get(session, "/api/v1/me/accessible-groups"))).isEmpty();
        problem(session, "POST", "/api/v1/checkpoints", checkpoint(group), 403, "GROUP_NOT_ACCESSIBLE");
        assertThat(items(get(session, "/api/v1/checkpoints"))).isEmpty();
        approve(id, department);
        var created = request(session, "POST", "/api/v1/checkpoints", checkpoint(group), 201);
        assertThat(created.path("id").asLong()).isPositive();
        assertThat(ids(get(session, "/api/v1/checkpoints"))).containsExactly(created.path("id").asLong());
    }

    @Test
    void rejectionDeletesGrantsExpiresAllSessionsAndAllowsSameEmailAgain() {
        jdbc.update("insert into teacher_allowlist(email,department_id) values (?,?)", email("teacher"), department);
        long id = teacher("teacher", "Teacher");
        var first = login("teacher");
        var second = login("teacher");
        grant(id, Map.of("kind", "GROUP", "groupId", otherGroup), 201);
        assertThat(scope(id)).isEmpty();
        request(admin, "DELETE", teacherPath(id) + "/application", null, 204);
        problem(first, "GET", "/api/v1/auth/me", null, 401, "UNAUTHENTICATED");
        problem(second, "GET", "/api/v1/auth/me", null, 401, "UNAUTHENTICATED");
        problem(admin, "GET", teacherPath(id), null, 404, "NOT_FOUND");
        problem(admin, "DELETE", teacherPath(id) + "/application", null, 404, "NOT_FOUND");
        assertThat(ids(get(TEACHERS))).doesNotContain(id);
        problem(browser(), "POST", "/api/v1/auth/register/student", Map.of("email", email("teacher"), "password", PASSWORD, "lastName", UserNameFixture.last("Reserved"), "firstName", UserNameFixture.first("Reserved"), "middleName", UserNameFixture.middleOrEmpty("Reserved"), "facultyId", faculty, "groupId", group), 409, "EMAIL_RESERVED_FOR_TEACHER");
        long replacement = teacher("teacher", "Replacement");
        assertThat(replacement).isNotEqualTo(id);
        assertThat(ids(get(teacherPath(replacement) + "/grants"))).isEmpty();
        assertThat(scope(replacement)).isEmpty();
    }

    @Test
    void rejectionOfApprovedTeacherIsConflictWithoutChangingAccountOrSession() {
        long id = teacher("teacher", "Teacher");
        var session = login("teacher");
        approve(id, department);
        var before = get(teacherPath(id));
        problem(admin, "DELETE", teacherPath(id) + "/application", null, 409, "INVALID_STATE");
        assertThat(get(teacherPath(id))).isEqualTo(before);
        assertThat(scope(id)).containsExactly(group, sameNameGroup);
        assertThat(get(session, "/api/v1/auth/me").path("id").asLong()).isEqualTo(id);
    }

    @Test
    void rejectionOfMissingTeacherOrStudentIsNotFoundAndStudentKeepsSession() {
        long id = student("student", faculty, group);
        var session = login("student");
        problem(admin, "DELETE", teacherPath(ABSENT) + "/application", null, 404, "NOT_FOUND");
        problem(admin, "DELETE", teacherPath(id) + "/application", null, 404, "NOT_FOUND");
        assertThat(get(session, "/api/v1/auth/me").path("id").asLong()).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GROUP", "FACULTY", "FACULTY_COURSE", "COURSE"})
    void eachGrantKindExpandsScopeIsIdempotentAndRemovalNarrowsIt(String kind) {
        long id = teacher("teacher", "Teacher");
        var session = login("teacher");
        approve(id, department);
        var body = grantBody(kind);
        var created = grant(id, body, 201);
        assertThat(created.path("kind").asText()).isEqualTo(kind);
        if (body.containsKey("groupId")) assertThat(created.path("group").path("id").asLong()).isEqualTo(otherGroup);
        if (body.containsKey("facultyId")) assertThat(created.path("faculty").path("id").asLong()).isEqualTo(otherFaculty);
        if (body.containsKey("course")) assertThat(created.path("course").asInt()).isEqualTo(1);
        assertThat(grant(id, body, 200)).isEqualTo(created);
        assertThat(items(get(teacherPath(id) + "/grants"))).containsExactly(created);
        var expected = switch (kind) {
            case "FACULTY" -> List.of(otherGroup, group, sameNameGroup, olderGroup);
            case "COURSE" -> List.of(otherGroup, thirdGroup, group, sameNameGroup);
            default -> List.of(otherGroup, group, sameNameGroup);
        };
        assertThat(scope(id)).containsExactlyElementsOf(expected);
        teacherScope(session, expected);
        long grantId = created.path("id").asLong();
        request(admin, "DELETE", teacherPath(id) + "/grants/" + grantId, null, 204);
        assertThat(scope(id)).containsExactly(group, sameNameGroup);
        teacherScope(session, List.of(group, sameNameGroup));
        assertThat(items(get(teacherPath(id) + "/grants"))).isEmpty();
        problem(admin, "DELETE", teacherPath(id) + "/grants/" + grantId, null, 404, "NOT_FOUND");
    }

    @Test
    void grantsBeforeApprovalAreListedByIdButDoNotConferAccess() {
        long id = teacher("teacher", "Teacher");
        var session = login("teacher");
        var first = grant(id, grantBody("GROUP"), 201);
        var second = grant(id, grantBody("FACULTY"), 201);
        assertThat(items(get(teacherPath(id) + "/grants"))).containsExactly(first, second);
        assertThat(scope(id)).isEmpty();
        assertThat(ids(get(session, "/api/v1/me/accessible-groups"))).isEmpty();
        approve(id, department);
        request(admin, "DELETE", teacherPath(id) + "/grants/" + first.path("id").asLong(), null, 204);
        assertThat(scope(id)).containsExactly(otherGroup, group, sameNameGroup, olderGroup);
        assertThat(items(get(teacherPath(id) + "/grants"))).containsExactly(second);
    }

    @ParameterizedTest
    @CsvSource({"GROUP,facultyId", "GROUP,course", "FACULTY,groupId", "FACULTY,course", "FACULTY_COURSE,groupId", "COURSE,facultyId", "COURSE,groupId"})
    void extraGrantFieldsAreRejectedWithoutCreatingGrant(String kind, String extra) {
        long id = teacher("teacher", "Teacher");
        var body = new HashMap<>(grantBody(kind));
        body.put(extra, extra.equals("groupId") ? otherGroup : otherFaculty);
        invalid("POST", teacherPath(id) + "/grants", body, extra);
        assertThat(items(get(teacherPath(id) + "/grants"))).isEmpty();
        assertThat(scope(id)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"GROUP,groupId", "FACULTY,facultyId", "FACULTY_COURSE,facultyId"})
    void unknownGrantReferencesAreFieldErrors(String kind, String field) {
        long id = teacher("teacher", "Teacher");
        var body = new HashMap<>(grantBody(kind));
        body.put(field, ABSENT);
        invalid("POST", teacherPath(id) + "/grants", body, field);
        assertThat(items(get(teacherPath(id) + "/grants"))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"COURSE,0", "COURSE,7", "FACULTY_COURSE,0", "FACULTY_COURSE,7"})
    void courseOutsideOneThroughSixIsRejected(String kind, int course) {
        long id = teacher("teacher", "Teacher");
        var body = new HashMap<>(grantBody(kind));
        body.put("course", course);
        invalid("POST", teacherPath(id) + "/grants", body, "course");
        assertThat(items(get(teacherPath(id) + "/grants"))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"GROUP,groupId", "FACULTY,facultyId", "FACULTY_COURSE,course", "FACULTY_COURSE,facultyId", "COURSE,course"})
    void nullOrMissingRequiredGrantFieldIsRejected(String kind, String field) {
        long id = teacher("teacher", "Teacher");
        var body = new HashMap<>(grantBody(kind));
        body.remove(field);
        invalid("POST", teacherPath(id) + "/grants", body, field);
        body.put(field, null);
        invalid("POST", teacherPath(id) + "/grants", body, field);
        assertThat(items(get(teacherPath(id) + "/grants"))).isEmpty();
    }

    @Test
    void deletingForeignOrMissingGrantDoesNotChangeOwnersGrantsOrScope() {
        long owner = teacher("owner", "Owner");
        long other = teacher("other", "Other");
        approve(owner, department);
        var granted = grant(owner, grantBody("GROUP"), 201);
        var before = get(teacherPath(owner) + "/grants");
        var scope = scope(owner);
        problem(admin, "DELETE", teacherPath(other) + "/grants/" + granted.path("id").asLong(), null, 404, "NOT_FOUND");
        problem(admin, "DELETE", teacherPath(owner) + "/grants/" + ABSENT, null, 404, "NOT_FOUND");
        assertThat(get(teacherPath(owner) + "/grants")).isEqualTo(before);
        assertThat(scope(owner)).isEqualTo(scope);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COURSE", "FACULTY_COURSE"})
    void courseChangesAtExactlySeptemberFirstMidnightMoscow(String kind) {
        long id = teacher("teacher", "Teacher");
        var session = login("teacher");
        approve(id, department);
        grant(id, grantBody(kind), 201);
        clock.set(Instant.parse("2026-08-31T20:59:59.999999999Z"));
        assertThat(scope(id)).contains(olderGroup).doesNotContain(otherGroup, thirdGroup);
        teacherScope(session, scope(id));
        clock.set(Instant.parse("2026-08-31T21:00:00Z"));
        assertThat(scope(id)).contains(otherGroup).doesNotContain(olderGroup);
        if (kind.equals("COURSE")) assertThat(scope(id)).contains(thirdGroup);
        else assertThat(scope(id)).doesNotContain(thirdGroup);
        teacherScope(session, scope(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"COURSE", "FACULTY_COURSE"})
    void sixthCourseIsAcceptedAndFutureOrSeventhCourseGroupsAreExcluded(String kind) {
        long sixth = group(otherFaculty, "sixth", "Sixth", 2021);
        long seventh = group(otherFaculty, "seventh", "Seventh", 2020);
        long future = group(otherFaculty, "future", "Future", 2027);
        long id = teacher("teacher", "Teacher");
        var session = login("teacher");
        approve(id, department);
        var body = new HashMap<>(grantBody(kind));
        body.put("course", 6);
        var created = grant(id, body, 201);
        assertThat(created.path("course").asInt()).isEqualTo(6);
        assertThat(scope(id)).containsExactly(group, sameNameGroup, sixth).doesNotContain(seventh, future);
        teacherScope(session, scope(id));
    }

    private Map<String, Object> grantBody(String kind) {
        return switch (kind) {
            case "GROUP" -> Map.of("kind", kind, "groupId", otherGroup);
            case "FACULTY" -> Map.of("kind", kind, "facultyId", otherFaculty);
            case "FACULTY_COURSE" -> Map.of("kind", kind, "facultyId", otherFaculty, "course", 1);
            default -> Map.of("kind", kind, "course", 1);
        };
    }
}

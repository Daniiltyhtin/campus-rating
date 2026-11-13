package ru.campusrating.directory;

import ru.campusrating.support.UserNameFixture;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.campusrating.support.AdminHttpTestSupport;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class AdminDirectoryIT extends AdminHttpTestSupport {
    @ParameterizedTest
    @ValueSource(strings = {"guest", "student", "teacher", "temporaryAdmin"})
    void allTwelveOperationsEnforceAdministratorAndPasswordChange(String actor) {
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
        for (String type : List.of("faculties", "groups", "departments")) {
            String path = DIRECTORY + type;
            var before = get(path);
            problem(session, "GET", path, null, status, code);
            problem(session, "POST", path, body(type, "denied", faculty), status, code);
            problem(session, "PUT", path + "/" + fixtureId(type), body(type, "denied", otherFaculty), status, code);
            problem(session, "DELETE", path + "/" + fixtureId(type), null, status, code);
            assertThat(get(path)).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"faculties", "groups", "departments"})
    void createTrimsCodeAndNameAndIsVisibleInPublicDirectory(String type) {
        var input = new HashMap<>(body(type, "created", faculty));
        input.put("code", "  " + prefix + "created  ");
        input.put("name", "  Created  ");
        var created = request(admin, "POST", DIRECTORY + type, input, 201);
        assertThat(created.path("id").asLong()).isPositive();
        assertThat(created.path("code").asText()).isEqualTo(prefix + "created");
        assertThat(created.path("name").asText()).isEqualTo("Created");
        if (!type.equals("faculties")) assertThat(created.path("faculty").path("id").asLong()).isEqualTo(faculty);
        if (type.equals("groups")) assertThat(created.path("admissionYear").asInt()).isEqualTo(2026);
        assertThat(items(get(DIRECTORY + type))).contains(created);
        var visible = items(get(browser(), publicPath(type, faculty))).stream()
                .filter(n -> n.path("id").asLong() == created.path("id").asLong()).toList();
        assertThat(visible).hasSize(1);
        assertThat(visible.getFirst().path("name").asText()).isEqualTo("Created");
        if (!type.equals("groups")) assertThat(visible.getFirst().path("code").asText()).isEqualTo(prefix + "created");
    }

    @ParameterizedTest
    @ValueSource(strings = {"faculties", "groups", "departments"})
    void repeatedCodeIsConflictWithNoSecondRecord(String type) {
        var input = body(type, "duplicate", faculty);
        request(admin, "POST", DIRECTORY + type, input, 201);
        var before = get(DIRECTORY + type);
        problem(admin, "POST", DIRECTORY + type, input, 409, "DIRECTORY_CODE_TAKEN");
        var padded = new HashMap<>(input);
        padded.put("code", " " + input.get("code") + " ");
        padded.put("name", "Another name");
        problem(admin, "POST", DIRECTORY + type, padded, 409, "DIRECTORY_CODE_TAKEN");
        assertThat(get(DIRECTORY + type)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"groups", "departments"})
    void unknownFacultyOnCreateOrUpdateIsFieldErrorAndChangesNothing(String type) {
        var before = get(DIRECTORY + type);
        var input = body(type, "invalid", ABSENT);
        invalid("POST", DIRECTORY + type, input, "facultyId");
        invalid("PUT", DIRECTORY + type + "/" + fixtureId(type), input, "facultyId");
        assertThat(get(DIRECTORY + type)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"faculties", "groups", "departments"})
    void updateFullyReplacesFieldsAndPreservesIdentity(String type) {
        var input = new HashMap<>(body(type, "updated", otherFaculty));
        input.put("code", " " + prefix + "updated ");
        input.put("name", " Updated ");
        if (type.equals("groups")) input.put("admissionYear", 2024);
        long id = fixtureId(type);
        var updated = request(admin, "PUT", DIRECTORY + type + "/" + id, input, 200);
        assertThat(updated.path("id").asLong()).isEqualTo(id);
        assertThat(updated.path("code").asText()).isEqualTo(prefix + "updated");
        assertThat(updated.path("name").asText()).isEqualTo("Updated");
        if (!type.equals("faculties")) assertThat(updated.path("faculty").path("id").asLong()).isEqualTo(otherFaculty);
        if (type.equals("groups")) assertThat(updated.path("admissionYear").asInt()).isEqualTo(2024);
        assertThat(items(get(DIRECTORY + type))).contains(updated);
        assertThat(ids(get(browser(), publicPath(type, otherFaculty)))).contains(id);
        if (!type.equals("faculties")) assertThat(ids(get(browser(), publicPath(type, faculty)))).doesNotContain(id);
        assertThat(request(admin, "PUT", DIRECTORY + type + "/" + id, input, 200)).isEqualTo(updated);
    }

    @Test
    void movingGroupToAnotherFacultyKeepsStudentAndMembership() {
        long student = student("student", faculty, group);
        long teacher = teacher("teacher", "Teacher");
        approve(teacher, department);
        request(login("teacher"), "POST", "/api/v1/memberships/" + student + "/approval", null, 204);
        var session = login("student");
        var before = get(session, "/api/v1/auth/me");
        request(admin, "PUT", DIRECTORY + "groups/" + group, body("groups", "moved", otherFaculty), 200);
        var after = get(session, "/api/v1/auth/me");
        assertThat(after.path("id").asLong()).isEqualTo(student);
        assertThat(after.path("group").path("id").asLong()).isEqualTo(group);
        assertThat(after.path("group").path("faculty").path("id").asLong()).isEqualTo(otherFaculty);
        assertThat(before.path("membershipState").asText()).isEqualTo("ACTIVE");
        assertThat(after.path("membershipState").asText()).isEqualTo("ACTIVE");
        assertThat(ids(get(browser(), publicPath("groups", faculty)))).doesNotContain(group);
        assertThat(ids(get(browser(), publicPath("groups", otherFaculty)))).contains(group);
    }

    @ParameterizedTest
    @ValueSource(strings = {"faculties", "groups", "departments"})
    void updateToTakenCodeDoesNotChangeExistingRecord(String type) {
        var created = request(admin, "POST", DIRECTORY + type, body(type, "taken", faculty), 201);
        var input = new HashMap<>(body(type, "changed", otherFaculty));
        input.put("code", created.path("code").asText());
        var before = get(DIRECTORY + type);
        problem(admin, "PUT", DIRECTORY + type + "/" + fixtureId(type), input, 409, "DIRECTORY_CODE_TAKEN");
        assertThat(get(DIRECTORY + type)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"faculties", "groups", "departments"})
    void emptyRecordCanBeDeletedOnceAndIsGoneFromPublicDirectory(String type) {
        var created = request(admin, "POST", DIRECTORY + type, body(type, "empty", faculty), 201);
        long id = created.path("id").asLong();
        request(admin, "DELETE", DIRECTORY + type + "/" + id, null, 204);
        assertThat(ids(get(DIRECTORY + type))).doesNotContain(id);
        assertThat(ids(get(browser(), publicPath(type, faculty)))).doesNotContain(id);
        problem(admin, "DELETE", DIRECTORY + type + "/" + id, null, 404, "NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"student", "checkpoint", "grant", "activity"})
    void groupWithDependentRecordCannotBeDeletedAndDependencySurvives(String dependency) {
        Browser owner = null;
        long ownerId = 0;
        long resource = 0;
        if (dependency.equals("student")) {
            ownerId = student("student", faculty, group);
            owner = login("student");
        } else {
            ownerId = teacher("teacher", "Teacher");
            owner = login("teacher");
            approve(ownerId, otherDepartment);
            grant(ownerId, Map.of("kind", "GROUP", "groupId", group), 201);
            if (dependency.equals("checkpoint")) {
                resource = request(owner, "POST", "/api/v1/checkpoints", checkpoint(group), 201).path("id").asLong();
                removeGroupGrant(ownerId);
            } else if (dependency.equals("activity")) {
                resource = request(owner, "POST", "/api/v1/activities", Map.of("title", "Activity", "description", "",
                        "type", "ACADEMIC", "startsAt", clock.instant().plusSeconds(3600).toString(),
                        "groupIds", List.of(group), "maxPoints", 50), 201).path("id").asLong();
                removeGroupGrant(ownerId);
            }
        }
        var before = get(DIRECTORY + "groups");
        problem(admin, "DELETE", DIRECTORY + "groups/" + group, null, 409, "DIRECTORY_IN_USE");
        assertThat(get(DIRECTORY + "groups")).isEqualTo(before);
        assertThat(ids(get(browser(), publicPath("groups", faculty)))).contains(group);
        switch (dependency) {
            case "student" -> {
                var me = get(owner, "/api/v1/auth/me");
                assertThat(me.path("id").asLong()).isEqualTo(ownerId);
                assertThat(me.path("group").path("id").asLong()).isEqualTo(group);
            }
            case "checkpoint" -> assertThat(get(owner, "/api/v1/checkpoints/" + resource).path("id").asLong()).isEqualTo(resource);
            case "activity" -> assertThat(get(owner, "/api/v1/activities/" + resource).path("id").asLong()).isEqualTo(resource);
            default -> assertThat(scope(ownerId)).contains(group);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"group", "department", "grant"})
    void facultyWithDependentRecordCannotBeDeleted(String dependency) {
        long target = faculty("used", "Used");
        long child;
        if (dependency.equals("group")) child = group(target, "usedGroup", "Child", 2026);
        else if (dependency.equals("department")) child = department(target, "usedDepartment");
        else {
            child = teacher("teacher", "Teacher");
            grant(child, Map.of("kind", "FACULTY", "facultyId", target), 201);
        }
        var before = get(DIRECTORY + "faculties");
        problem(admin, "DELETE", DIRECTORY + "faculties/" + target, null, 409, "DIRECTORY_IN_USE");
        assertThat(get(DIRECTORY + "faculties")).isEqualTo(before);
        assertThat(ids(get(browser(), publicPath("faculties", target)))).contains(target);
        if (dependency.equals("grant")) {
            assertThat(items(get(teacherPath(child) + "/grants"))).singleElement()
                    .satisfies(n -> assertThat(n.path("faculty").path("id").asLong()).isEqualTo(target));
        } else {
            assertThat(ids(get(browser(), publicPath(dependency.equals("group") ? "groups" : "departments", target)))).contains(child);
        }
    }

    @Test
    void departmentWithTeacherCannotBeDeletedAndTeacherSurvives() {
        long id = teacher("teacher", "Teacher");
        var before = get(teacherPath(id));
        problem(admin, "DELETE", DIRECTORY + "departments/" + department, null, 409, "DIRECTORY_IN_USE");
        assertThat(get(teacherPath(id))).isEqualTo(before);
        assertThat(ids(get(browser(), publicPath("departments", faculty)))).contains(department);
    }

    @Test
    void departmentWithAllowlistEntryCannotBeDeleted() {
        jdbc.update("insert into teacher_allowlist(email,department_id) values (?,?)", email("reserved"), department);
        var before = get(DIRECTORY + "departments");
        problem(admin, "DELETE", DIRECTORY + "departments/" + department, null, 409, "DIRECTORY_IN_USE");
        assertThat(get(DIRECTORY + "departments")).isEqualTo(before);
        problem(browser(), "POST", "/api/v1/auth/register/student", Map.of("email", email("reserved"), "password", PASSWORD, "lastName", UserNameFixture.last("Reserved"), "firstName", UserNameFixture.first("Reserved"), "middleName", UserNameFixture.middleOrEmpty("Reserved"), "facultyId", faculty, "groupId", group), 409, "EMAIL_RESERVED_FOR_TEACHER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"faculties", "groups", "departments"})
    void listsAreCompleteAndSortedByNameThenId(String type) {
        var before = ids(get(DIRECTORY + type));
        var firstBody = new HashMap<>(body(type, "sort1", faculty));
        firstBody.put("name", "AAA");
        var secondBody = new HashMap<>(body(type, "sort2", faculty));
        secondBody.put("name", "AAA");
        var thirdBody = new HashMap<>(body(type, "sort3", faculty));
        thirdBody.put("name", "ZZZ");
        long z = request(admin, "POST", DIRECTORY + type, thirdBody, 201).path("id").asLong();
        long a1 = request(admin, "POST", DIRECTORY + type, firstBody, 201).path("id").asLong();
        long a2 = request(admin, "POST", DIRECTORY + type, secondBody, 201).path("id").asLong();
        var list = get(DIRECTORY + type);
        sorted(list, "name");
        assertThat(ids(list)).containsExactlyInAnyOrderElementsOf(java.util.stream.Stream.concat(before.stream(), java.util.stream.Stream.of(z, a1, a2)).toList());
        assertThat(ids(list).indexOf(a1)).isLessThan(ids(list).indexOf(a2));
        assertThat(ids(list).indexOf(a2)).isLessThan(ids(list).indexOf(z));
    }

    @ParameterizedTest
    @ValueSource(strings = {"faculties", "groups", "departments"})
    void updatingMissingRecordReturnsNotFound(String type) {
        var before = get(DIRECTORY + type);
        problem(admin, "PUT", DIRECTORY + type + "/" + ABSENT, body(type, "missing", faculty), 404, "NOT_FOUND");
        assertThat(get(DIRECTORY + type)).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"faculties,code", "faculties,name", "groups,code", "groups,name", "departments,code", "departments,name"})
    void blankDirectoryFieldsAreRejectedOnCreateAndUpdate(String type, String field) {
        var before = get(DIRECTORY + type);
        var input = new HashMap<>(body(type, "blank", faculty));
        input.put(field, "   ");
        invalid("POST", DIRECTORY + type, input, field);
        invalid("PUT", DIRECTORY + type + "/" + fixtureId(type), input, field);
        assertThat(get(DIRECTORY + type)).isEqualTo(before);
    }

    @Test
    void sameCodeIsAllowedAcrossDirectoriesAndCodesAreCaseSensitive() {
        for (String type : List.of("faculties", "groups", "departments")) {
            var first = request(admin, "POST", DIRECTORY + type, body(type, "MiXeD", faculty), 201);
            var second = request(admin, "POST", DIRECTORY + type, body(type, "mixed", faculty), 201);
            assertThat(first.path("code").asText()).isEqualTo(prefix + "MiXeD");
            assertThat(second.path("code").asText()).isEqualTo(prefix + "mixed");
            assertThat(first.path("id").asLong()).isNotEqualTo(second.path("id").asLong());
            assertThat(items(get(DIRECTORY + type))).contains(first, second);
        }
    }

    private void removeGroupGrant(long teacher) {
        long id = ids(get(teacherPath(teacher) + "/grants")).getFirst();
        request(admin, "DELETE", teacherPath(teacher) + "/grants/" + id, null, 204);
    }

    private long fixtureId(String type) {
        return switch (type) {
            case "faculties" -> faculty;
            case "groups" -> group;
            default -> department;
        };
    }

    private Map<String, Object> body(String type, String suffix, long facultyId) {
        return switch (type) {
            case "faculties" -> Map.of("code", prefix + suffix, "name", suffix);
            case "groups" -> Map.of("code", prefix + suffix, "name", suffix, "facultyId", facultyId, "admissionYear", 2026);
            default -> Map.of("code", prefix + suffix, "name", suffix, "facultyId", facultyId);
        };
    }

    private String publicPath(String type, long facultyId) {
        return "/api/v1/directory/faculties" + (type.equals("faculties") ? "" : "/" + facultyId + "/" + type);
    }
}

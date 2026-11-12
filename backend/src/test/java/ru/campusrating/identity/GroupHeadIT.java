package ru.campusrating.identity;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.campusrating.support.AdminHttpTestSupport;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class GroupHeadIT extends AdminHttpTestSupport {
    private static final String HEADS = "/api/v1/admin/group-heads";
    private static final String ME = "/api/v1/auth/me";

    @AfterEach
    void cleanupHeads() {
        if (Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('public.group_head') is not null", Boolean.class)))
            jdbc.update("delete from group_head where student_id in (select id from app_user where email like ?) or group_id in (select id from study_group where code like ?)",
                    prefix + "%", prefix + "%");
    }

    @Test
    void onlyAdministratorManagesHeadsAndGuestIsUnauthenticated() {
        long candidate = activeStudent("candidate", faculty, group);
        long teacherId = teacher("teacher", "Teacher");
        approve(teacherId, department);
        var teacher = login("teacher");
        var student = login("candidate");
        for (var session : List.of(teacher, student)) {
            problem(session, "GET", HEADS, null, 403, "FORBIDDEN");
            problem(session, "PUT", head(group), Map.of("studentId", candidate), 403, "FORBIDDEN");
            problem(session, "DELETE", head(group), null, 403, "FORBIDDEN");
        }
        problem(browser(), "GET", HEADS, null, 401, "UNAUTHENTICATED");
        problem(browser(), "PUT", head(group), Map.of("studentId", candidate), 401, "UNAUTHENTICATED");
        problem(browser(), "DELETE", head(group), null, 401, "UNAUTHENTICATED");
        assertThat(headOf(group)).isNull();
    }

    @Test
    void assignedActiveStudentIsListedSeesFlagAndApprovesOwnGroup() {
        long head = activeStudent("head", faculty, group);
        long pending = student("pending", faculty, group);
        var assigned = request(admin, "PUT", head(group), Map.of("studentId", head), 200);
        assertThat(assigned.path("groupId").asLong()).isEqualTo(group);
        assertThat(assigned.at("/student/id").asLong()).isEqualTo(head);
        assertThat(assigned.at("/student/fullName").asText()).isEqualTo("head");
        assertThat(headOf(group)).isEqualTo(head);
        var session = login("head");
        assertThat(get(session, ME).path("groupHead").asBoolean()).isTrue();
        assertThat(pendingIds(session)).containsExactly(pending);
        request(session, "POST", approval(pending), null, 204);
        assertThat(get(login("pending"), ME).path("membershipState").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void newAssignmentReplacesPreviousHeadWhoLosesRights() {
        long first = activeStudent("first", faculty, group);
        long second = activeStudent("second", faculty, group);
        long pending = student("pending", faculty, group);
        request(admin, "PUT", head(group), Map.of("studentId", first), 200);
        request(admin, "PUT", head(group), Map.of("studentId", second), 200);
        assertThat(items(get(HEADS)).stream().filter(n -> n.path("groupId").asLong() == group).toList()).hasSize(1);
        assertThat(headOf(group)).isEqualTo(second);
        var previous = login("first");
        assertThat(get(previous, ME).path("groupHead").asBoolean(true)).isFalse();
        problem(previous, "POST", approval(pending), null, 403, "FORBIDDEN");
        problem(previous, "POST", rejection(pending), null, 403, "FORBIDDEN");
        assertThat(items(get(previous, "/api/v1/memberships/pending"))).isEmpty();
        var current = login("second");
        assertThat(get(current, ME).path("groupHead").asBoolean()).isTrue();
        request(current, "POST", approval(pending), null, 204);
        request(admin, "PUT", head(group), Map.of("studentId", second), 200);
        assertThat(headOf(group)).isEqualTo(second);
    }

    @Test
    void onlyActiveStudentOfThisGroupCanBeAssigned() {
        long pending = student("pending", faculty, group);
        long rejected = student("rejected", faculty, group);
        long elsewhere = activeStudent("elsewhere", faculty, sameNameGroup);
        long teacherId = teacher("teacher", "Teacher");
        approve(teacherId, department);
        request(login("teacher"), "POST", rejection(rejected), null, 204);
        for (long candidate : List.of(pending, rejected, elsewhere, teacherId, ABSENT))
            invalid("PUT", head(group), Map.of("studentId", candidate), "studentId");
        assertThat(headOf(group)).isNull();
        long active = activeStudent("active", faculty, group);
        problem(admin, "PUT", head(ABSENT), Map.of("studentId", active), 404, "NOT_FOUND");
        assertThat(headOf(group)).isNull();
    }

    @Test
    void removalRevokesRightsAndRepeatedOrMissingRemovalIsNotFound() {
        long head = activeStudent("head", faculty, group);
        long pending = student("pending", faculty, group);
        problem(admin, "DELETE", head(group), null, 404, "NOT_FOUND");
        request(admin, "PUT", head(group), Map.of("studentId", head), 200);
        var session = login("head");
        request(admin, "DELETE", head(group), null, 204);
        assertThat(headOf(group)).isNull();
        assertThat(get(session, ME).path("groupHead").asBoolean(true)).isFalse();
        problem(session, "POST", approval(pending), null, 403, "FORBIDDEN");
        problem(session, "POST", rejection(pending), null, 403, "FORBIDDEN");
        assertThat(items(get(session, "/api/v1/memberships/pending"))).isEmpty();
        problem(admin, "DELETE", head(group), null, 404, "NOT_FOUND");
        problem(admin, "DELETE", head(ABSENT), null, 404, "NOT_FOUND");
    }

    @Test
    void headHasRightsOnlyInOwnGroup() {
        long head = activeStudent("head", faculty, group);
        long own = student("own", faculty, group);
        long neighbour = student("neighbour", faculty, sameNameGroup);
        long foreign = student("foreign", otherFaculty, otherGroup);
        request(admin, "PUT", head(group), Map.of("studentId", head), 200);
        var session = login("head");
        assertThat(pendingIds(session)).containsExactly(own);
        for (long target : List.of(neighbour, foreign)) {
            problem(session, "POST", approval(target), null, 403, "FORBIDDEN");
            problem(session, "POST", rejection(target), null, 403, "FORBIDDEN");
        }
        request(session, "POST", rejection(own), null, 204);
        assertThat(get(login("own"), ME).path("membershipState").asText()).isEqualTo("REJECTED");
        assertThat(get(login("neighbour"), ME).path("membershipState").asText()).isEqualTo("PENDING");
        problem(session, "GET", "/api/v1/admin/group-heads", null, 403, "FORBIDDEN");
        problem(session, "GET", "/api/v1/groups/" + group + "/students", null, 403, "FORBIDDEN");
    }

    @Test
    void administratorReadsStudentsOfAnyGroupToChooseHead() {
        long active = activeStudent("active", faculty, group);
        long pending = student("pending", faculty, group);
        long foreign = activeStudent("foreign", otherFaculty, otherGroup);
        var rows = items(get("/api/v1/groups/" + group + "/students"));
        assertThat(rows.stream().map(n -> n.path("id").asLong()).toList()).contains(active, pending).doesNotContain(foreign);
        assertThat(ids(get("/api/v1/groups/" + otherGroup + "/students"))).containsExactly(foreign);
    }

    private String head(long groupId) { return HEADS + "/" + groupId; }
    private String approval(long student) { return "/api/v1/memberships/" + student + "/approval"; }
    private String rejection(long student) { return "/api/v1/memberships/" + student + "/rejection"; }

    private Long headOf(long groupId) {
        return items(get(HEADS)).stream().filter(n -> n.path("groupId").asLong() == groupId)
                .map(n -> n.at("/student/id").asLong()).findFirst().orElse(null);
    }

    private List<Long> pendingIds(Browser session) {
        return items(get(session, "/api/v1/memberships/pending")).stream().map((JsonNode n) -> n.path("studentId").asLong()).toList();
    }

    private long activeStudent(String suffix, long facultyId, long groupId) {
        long id = student(suffix, facultyId, groupId);
        jdbc.update("update group_membership set state='ACTIVE',activated_at=? where student_id=?", Timestamp.from(clock.instant()), id);
        return id;
    }
}

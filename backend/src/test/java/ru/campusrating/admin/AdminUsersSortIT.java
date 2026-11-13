package ru.campusrating.admin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.campusrating.support.U13HttpTestSupport;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUsersSortIT extends U13HttpTestSupport {
    private List<JsonNode> list(String sort, String direction) {
        return items(get("/api/v1/admin/users?search=" + prefix + "&sort=" + sort + (direction == null ? "" : "&direction=" + direction)));
    }
    private static List<String> field(List<JsonNode> rows, String name) { return rows.stream().map(row -> row.path(name).asText()).toList(); }
    private void active(long student) {
        jdbc.update("update group_membership set state='ACTIVE',activated_at=? where student_id=?", java.sql.Timestamp.from(clock.instant()), student);
    }
    private void people() {
        clock.set(Instant.parse("2026-11-12T10:00:00Z")); active(student("c-student", faculty, group));
        clock.set(Instant.parse("2026-11-12T11:00:00Z")); active(student("a-student", otherFaculty, otherGroup));
        clock.set(Instant.parse("2026-11-12T12:00:00Z")); approve(teacher("b-teacher", "Bteacher"), department);
        clock.set(Instant.parse("2026-11-12T13:00:00Z")); teacher("d-teacher", "Dteacher");
    }

    @Test void rejectedMembershipIsListedFilteredAndSortedBothWays() {
        people();
        student("pending-student", faculty, group);
        long rejected = student("rejected-student", faculty, group);
        request(login("b-teacher"), "POST", "/api/v1/memberships/" + rejected + "/rejection", null, 204);

        var unfiltered = items(get("/api/v1/admin/users?search=" + prefix));
        assertThat(unfiltered.stream().filter(row -> row.path("id").asLong() == rejected)
                .map(row -> row.path("status").asText()).toList()).containsExactly("MEMBERSHIP_REJECTED");
        var filtered = items(get("/api/v1/admin/users?search=" + prefix + "&status=MEMBERSHIP_REJECTED"));
        assertThat(filtered.stream().map(row -> row.path("id").asLong()).toList()).containsExactly(rejected);
        assertThat(field(list("status", "asc"), "status")).containsExactly(
                "PENDING", "MEMBERSHIP_PENDING", "MEMBERSHIP_REJECTED", "APPROVED", "MEMBERSHIP_ACTIVE", "MEMBERSHIP_ACTIVE", "");
        assertThat(field(list("status", "desc"), "status")).containsExactly(
                "MEMBERSHIP_ACTIVE", "MEMBERSHIP_ACTIVE", "APPROVED", "MEMBERSHIP_REJECTED", "MEMBERSHIP_PENDING", "PENDING", "");
    }

    @Test void emailSortsBothWays() {
        people();
        var asc = field(list("email", "asc"), "email");
        assertThat(asc).isSortedAccordingTo(Comparator.naturalOrder());
        assertThat(field(list("email", "desc"), "email")).isEqualTo(asc.reversed());
    }

    @Test void roleAndStatusUseDisplayRanks() {
        people();
        assertThat(field(list("role", "asc"), "role")).containsExactly("ADMIN", "TEACHER", "TEACHER", "STUDENT", "STUDENT");
        assertThat(field(list("role", "desc"), "role")).containsExactly("STUDENT", "STUDENT", "TEACHER", "TEACHER", "ADMIN");
        assertThat(field(list("status", "asc"), "status")).containsExactly("PENDING", "APPROVED", "MEMBERSHIP_ACTIVE", "MEMBERSHIP_ACTIVE", "");
    }

    @Test void unitSortsByGroupOrDepartmentNameWithAdministratorsLast() {
        people();
        var units = list("unit", "asc").stream().map(row -> row.path("group").isObject() ? row.path("group").path("name").asText()
                : row.path("department").isObject() ? row.path("department").path("name").asText() : null).toList();
        assertThat(units.getLast()).isNull();
        var named = units.subList(0, units.size() - 1);
        assertThat(named).doesNotContainNull();
        assertThat(new ArrayList<>(named)).isEqualTo(named.stream().sorted().toList());
    }

    @Test void createdDefaultsToNewestFirstAndCanBeReversed() {
        people();
        String students = "/api/v1/admin/users?search=" + prefix + "&role=STUDENT&sort=created";
        assertThat(field(items(get(students)), "createdAt")).containsExactly("2026-11-12T11:00:00Z", "2026-11-12T10:00:00Z");
        assertThat(field(items(get(students + "&direction=asc")), "createdAt")).containsExactly("2026-11-12T10:00:00Z", "2026-11-12T11:00:00Z");
    }

    @Test void pagesFollowTheChosenOrderWithoutDuplicatesAndRejectForeignCursor() {
        for (int i = 0; i < 120; i++) student(String.format("p%03d", i), faculty, group);
        var seen = new ArrayList<String>();
        String cursor = null;
        do {
            var body = get("/api/v1/admin/users?search=" + prefix + "p&sort=email&direction=desc" + (cursor == null ? "" : "&cursor=" + cursor));
            seen.addAll(field(items(body), "email"));
            cursor = body.path("nextCursor").isNull() ? null : body.path("nextCursor").asText();
        } while (cursor != null);
        assertThat(seen).hasSize(120).doesNotHaveDuplicates().isSortedAccordingTo(Comparator.reverseOrder());
        var first = get("/api/v1/admin/users?search=" + prefix + "p&sort=email&direction=desc");
        problem(admin, "GET", "/api/v1/admin/users?search=" + prefix + "p&sort=email&direction=asc&cursor=" + first.path("nextCursor").asText(),
                null, 400, "VALIDATION_FAILED");
    }
}

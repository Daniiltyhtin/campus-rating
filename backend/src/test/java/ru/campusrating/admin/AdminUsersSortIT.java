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

    private long named(String suffix, String last, String first, String middle) {
        return jdbc.queryForObject("insert into app_user(email,role,last_name,first_name,middle_name) values (?,'STUDENT',?,?,?) returning id",
                Long.class, email("names-" + suffix), last, first, middle);
    }
    private List<Long> nameIds(String query) {
        return ids(get("/api/v1/admin/users?search=" + prefix + "names-" + query));
    }

    @Test @org.junit.jupiter.api.DisplayName("Covers AE19: last name ties use first name ascending")
    void lastNameUsesFirstName() {
        long maria = named("m", "Иванова", "Мария", null);
        long anna = named("a", "Иванова", "Анна", "Олеговна");
        assertThat(nameIds("&sort=lastName&direction=asc")).containsExactly(anna, maria);
    }

    @Test @org.junit.jupiter.api.DisplayName("Covers AE19a: empty middle comes first as secondary key")
    void secondaryMiddleNullsFirst() {
        long full = named("full", "Иванов", "Иван", "Иванович");
        long shortName = named("short", "Иванов", "Иван", null);
        for (String sort : List.of("lastName", "firstName")) for (String direction : List.of("asc", "desc")) {
            assertThat(nameIds("&sort=" + sort + "&direction=" + direction)).containsExactly(shortName, full);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "Covers AE20: null primary middle stays last {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"asc", "desc"})
    void primaryMiddleNullsLast(String direction) {
        long empty = named("empty", "Иванова", "Мария", null);
        long a = named("a", "Сидоров", "Антон", "Антонович");
        long p = named("p", "Петров", "Павел", "Петрович");
        assertThat(nameIds("&sort=middleName&direction=" + direction)).containsExactlyElementsOf(
                direction.equals("asc") ? List.of(a, p, empty) : List.of(p, a, empty));
    }

    @Test @org.junit.jupiter.api.DisplayName("Covers AE19: descending first name retains ascending secondary keys and id")
    void firstNameDescending() {
        long ivan = named("ivan", "Андреев", "Иван", null);
        long second = named("second", "Борисов", "Яков", null);
        long first = named("first", "Андреев", "Яков", null);
        long tie = named("tie", "Андреев", "Яков", null);
        long middle = named("middle", "Андреев", "Яков", "Яковлевич");
        assertThat(nameIds("&sort=firstName&direction=desc")).containsExactly(first, tie, middle, second, ivan);
    }

    @Test @org.junit.jupiter.api.DisplayName("Covers AE23: omitted sort and direction default to last name ascending")
    void defaultNameOrder() {
        long z = named("z", "Яковлев", "Антон", null);
        long a = named("a", "Антонов", "Яков", null);
        assertThat(nameIds("")).containsExactly(a, z);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "Covers AE19, AE20: keyset pages {0} {1}")
    @org.junit.jupiter.params.provider.CsvSource({"lastName,asc", "lastName,desc", "firstName,asc", "firstName,desc", "middleName,asc", "middleName,desc"})
    void nameKeysetPages(String sort, String direction) {
        record Person(long id, String last, String first, String middle) {}
        var expected = new ArrayList<Person>();
        for (int i = 0; i < 120; i++) {
            String last = "Фамилия" + (i % 4), first = "Имя" + (i % 7), middle = i % 3 == 0 ? null : "Отчество" + (i % 5);
            expected.add(new Person(named("p" + i, last, first, middle), last, first, middle));
        }
        Comparator<String> text = Comparator.naturalOrder();
        Comparator<String> primary = direction.equals("desc") ? text.reversed() : text;
        Comparator<Person> order = switch (sort) {
            case "firstName" -> Comparator.comparing(Person::first, primary).thenComparing(Person::last).thenComparing(Person::middle, Comparator.nullsFirst(text));
            case "middleName" -> Comparator.comparing(Person::middle, Comparator.nullsLast(primary)).thenComparing(Person::last).thenComparing(Person::first);
            default -> Comparator.comparing(Person::last, primary).thenComparing(Person::first).thenComparing(Person::middle, Comparator.nullsFirst(text));
        };
        expected.sort(order.thenComparingLong(Person::id));
        var actual = new ArrayList<Long>();
        String cursor = null;
        for (int size : List.of(50, 50, 20)) {
            var page = get("/api/v1/admin/users?search=" + prefix + "names-&sort=" + sort + "&direction=" + direction
                    + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(items(page)).hasSize(size);
            actual.addAll(ids(page));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
            if (size == 50) assertThat(cursor).isNotBlank();
        }
        assertThat(cursor).isNull();
        assertThat(actual).containsExactlyElementsOf(expected.stream().map(Person::id).toList()).doesNotHaveDuplicates();
    }
}

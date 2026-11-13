package ru.campusrating.analytics;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.campusrating.support.AnalyticsTestSupport;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class GroupAnalyticsIT extends AnalyticsTestSupport {
    private String path(long id) { return "/api/v1/groups/" + id + "/analytics"; }
    private String period(String from, String to) { return path(group) + "?from=" + from + "&to=" + to; }
    private JsonNode analytics(String from, String to) { return get(teacherSession, period(from, to)); }

    @Test
    void ae1ReconstructsEachDayFromMembershipAndJournal() {
        long a = member("a", "Антонов Антон", "2026-11-01T00:00:00");
        long b = member("b", "Борисов Борис", "2026-11-03T10:00:00");
        entry(a, "AR", "INITIAL", 1000, "2026-11-01T00:00:00");
        entry(a, "AR", "PENALTY", -20, "2026-11-02T12:00:00");
        entry(b, "AR", "INITIAL", 1000, "2026-11-03T10:00:00");
        var result = analytics("2026-11-01", "2026-11-04");
        assertThat(result.path("group").path("id").asLong()).isEqualTo(group);
        assertThat(result.has("semesterStart")).isTrue();
        assertDays(result, List.of("1000.0", "980.0", "990.0", "990.0"), List.of(1, 1, 2, 2), "2026-11-01");
        assertThat(get(teacherSession, period("2026-11-01", "2026-11-04"))).isEqualTo(result);
    }

    @Test
    void ae2MissingDaysAndEmptyGroupKeepBudget() {
        long a = member("a", "Антонов Антон", "2026-11-05T00:00:00");
        entry(a, "AR", "INITIAL", 1000, "2026-11-05T00:00:00");
        var result = analytics("2026-11-01", "2026-11-06");
        assertThat(result.path("days")).hasSize(6);
        for (int i = 0; i < 4; i++) {
            assertThat(result.path("days").get(i).path("averageAr").isNull()).isTrue();
            assertThat(result.path("days").get(i).path("students").asInt()).isZero();
        }
        for (int i = 4; i < 6; i++) {
            assertThat(result.path("days").get(i).path("averageAr").asDouble()).isEqualTo(1000.0);
            assertThat(result.path("days").get(i).path("students").asInt()).isEqualTo(1);
        }
        var empty = get(teacherSession, path(sameNameGroup));
        assertThat(empty.path("days").isArray()).isTrue();
        assertThat(empty.path("days")).isEmpty();
        assertThat(empty.path("items").isArray()).isTrue();
        assertThat(empty.path("items")).isEmpty();
        assertThat(get(teacherSession, path(sameNameGroup) + "/budget").path("items")).hasSize(6);
    }

    @Test
    void ae3MoscowMidnightAndPendingMembership() {
        long c = member("c", "C", "2026-11-03T23:59:59");
        long d = member("d", "D", null);
        entry(c, "AR", "INITIAL", 1000, "2026-11-03T23:59:59");
        entry(c, "AR", "PENALTY", -5, "2026-11-04T00:00:00");
        entry(d, "AR", "INITIAL", 1000, "2026-11-01T00:00:00");
        assertDays(analytics("2026-11-03", "2026-11-04"), List.of("1000.0", "995.0"), List.of(1, 1), "2026-11-03");
    }

    @Test
    void ae3RoundsHalfUp() {
        for (int i = 0; i < 4; i++) {
            long student = member("round" + i, "Студент " + i, "2026-11-03T00:00:00");
            entry(student, "AR", "INITIAL", i == 2 ? 1001 : 1000, "2026-11-03T00:00:00");
        }
        assertDays(analytics("2026-11-03", "2026-11-03"), List.of("1000.3"), List.of(4), "2026-11-03");
    }

    @Test
    void ae4AndAe9PointsUsePeriodAndExcludeFutureMembershipWhileBudgetStays() {
        long a = member("a", "Антонов Антон", "2026-10-31T00:00:00");
        long b = member("b", "Борисов Борис", "2026-11-01T00:00:00");
        long e = member("e", "Егоров Егор", "2026-11-01T00:00:00");
        member("f", "Фёдоров Фёдор", "2026-11-14T00:00:00");
        for (long id : List.of(a, b, e)) entry(id, "AR", "INITIAL", 1000, "2026-10-31T00:00:00");
        entry(a, "AR", "PENALTY", -20, "2026-11-02T12:00:00");
        entry(e, "AR", "PENALTY", -10, "2026-11-02T12:00:00");
        entry(a, "BUA", "ACTIVITY_AWARD", 100, "2026-10-31T12:00:00");
        entry(a, "BUA", "ACTIVITY_AWARD", 30, "2026-11-04T12:00:00");
        entry(b, "BUA", "ACTIVITY_AWARD", 10, "2026-11-13T12:00:00");
        entry(b, "BOA", "ACTIVITY_AWARD", 50, "2026-11-13T12:00:00");
        var budget = get(teacherSession, path(group) + "/budget");
        var result = analytics("2026-11-01", "2026-11-13");
        assertThat(result.path("items")).hasSize(3);
        points(result, a, 30, 0, 980);
        points(result, b, 10, 50, 1000);
        points(result, e, 0, 0, 990);
        var shortPeriod = analytics("2026-11-01", "2026-11-04");
        assertThat(shortPeriod.path("days")).hasSize(4);
        points(shortPeriod, b, 0, 0, 1000);
        assertThat(get(teacherSession, path(group) + "/budget")).isEqualTo(budget);
    }

    @Test
    void ae5DefaultsAndValidation() {
        member("a", "Антонов Антон", "2025-11-13T00:00:00");
        assertThat(get(teacherSession, path(group)).path("days")).hasSize(30);
        assertThat(get(teacherSession, path(group)).path("days").get(0).path("date").asText()).isEqualTo("2026-10-15");
        assertThat(analytics("2025-11-13", "2026-11-13").path("days")).hasSize(366);
        for (var dates : List.of(List.of("2025-11-12", "2026-11-13"), List.of("2026-11-10", "2026-11-14"), List.of("2026-11-13", "2026-11-01"))) {
            var error = problem(teacherSession, "GET", period(dates.get(0), dates.get(1)), null, 400, "VALIDATION_FAILED");
            assertThat(error.path("fieldErrors")).anySatisfy(field ->
                    assertThat(field.path("field").asText()).isIn("from", "to"));
        }
    }

    @Test
    void ae6BudgetRowsRespectRoleCurrentLimitAndEmptyMonths() {
        long a = member("a", "Антонов Антон", "2026-11-01T00:00:00");
        novemberBudget(a);
        var own = get(teacherSession, path(group) + "/budget").path("items");
        assertThat(own).hasSize(6);
        for (int i = 0; i < 6; i++) {
            var row = own.get(i);
            assertThat(row.path("month").asText()).isEqualTo(java.time.YearMonth.of(2026, 11).minusMonths(i).toString());
            budget(row, 1000, i == 0 ? 950 : 0, i == 0 ? 50 : 1000, i == 0 ? 3 : 0, i == 0 ? 800 : 0, i == 0 ? 150 : 0);
            assertThat(row.path("teacher").path("id").asLong()).isEqualTo(petrov);
        }
        var all = get(path(group) + "/budget").path("items");
        assertThat(all).hasSize(7);
        assertThat(all.get(0).path("teacher").path("id").asLong()).isEqualTo(volkov);
        assertThat(all.get(1).path("teacher").path("id").asLong()).isEqualTo(petrov);
        budget(all.get(0), 1000, 100, 900, 1, 100, 0);
        assertThat(all.get(2).path("teacher").isNull()).isTrue();
        assertThat(all.get(2).path("remaining").isNull()).isTrue();
        for (int i = 2; i < 7; i++) {
            var empty = all.get(i);
            assertThat(empty.path("month").asText()).isEqualTo(java.time.YearMonth.of(2026, 10).minusMonths(i - 2).toString());
            assertThat(empty.path("teacher").isNull()).isTrue();
            assertThat(empty.path("remaining").isNull()).isTrue();
            assertThat(empty.path("limit").asInt()).isEqualTo(1000);
            for (String field : List.of("spent", "awards", "bua", "boa")) {
                assertThat(empty.has(field)).isTrue();
                assertThat(empty.path(field).asInt()).isZero();
            }
        }
        jdbc.update("insert into settings(key,value,type) values ('monthlyBudgetLimit','800','INTEGER')");
        var changed = get(teacherSession, path(group) + "/budget").path("items");
        changed.forEach(row -> assertThat(row.path("limit").asInt()).isEqualTo(800));
        assertThat(changed.get(0).path("remaining").asInt()).isZero();
    }

    @Test
    void ae7AccessIsSharedByBothOperationsAndMissingGroupIs404() {
        long a = member("a", "Антонов Антон", "2026-11-01T00:00:00");
        assertThat(a).isPositive();
        var studentSession = login("a");
        for (String suffix : List.of("", "/budget")) {
            get(teacherSession, path(group) + suffix);
            problem(teacherSession, "GET", path(otherGroup) + suffix, null, 403, "GROUP_NOT_ACCESSIBLE");
            get(path(otherGroup) + suffix);
            get(path(group) + suffix);
            problem(studentSession, "GET", path(group) + suffix, null, 403, "FORBIDDEN");
            problem(studentSession, "GET", path(ABSENT) + suffix, null, 404, "NOT_FOUND");
            problem(teacherSession, "GET", path(ABSENT) + suffix, null, 404, "NOT_FOUND");
        }
    }

    private void assertDays(JsonNode result, List<String> averages, List<Integer> counts, String start) {
        assertThat(result.path("days")).hasSize(averages.size());
        for (int i = 0; i < averages.size(); i++) {
            var day = result.path("days").get(i);
            assertThat(day.path("date").asText()).isEqualTo(java.time.LocalDate.parse(start).plusDays(i).toString());
            assertThat(day.path("averageAr").decimalValue()).isEqualByComparingTo(averages.get(i));
            assertThat(day.path("students").asInt()).isEqualTo(counts.get(i));
        }
    }

    private void points(JsonNode result, long student, int bua, int boa, int ar) {
        var row = result.path("items").valueStream().filter(item -> item.path("student").path("id").asLong() == student).findFirst().orElseThrow();
        assertThat(row.path("bua").asInt()).isEqualTo(bua);
        assertThat(row.path("boa").asInt()).isEqualTo(boa);
        assertThat(row.path("ar").asInt()).isEqualTo(ar);
    }

    private void budget(JsonNode row, int limit, int spent, int remaining, int awards, int bua, int boa) {
        assertThat(row.path("limit").asInt()).isEqualTo(limit);
        assertThat(row.path("spent").asInt()).isEqualTo(spent);
        assertThat(row.path("remaining").asInt()).isEqualTo(remaining);
        assertThat(row.path("awards").asInt()).isEqualTo(awards);
        assertThat(row.path("bua").asInt()).isEqualTo(bua);
        assertThat(row.path("boa").asInt()).isEqualTo(boa);
    }
}

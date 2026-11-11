package ru.campusrating.activity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.campusrating.activity.ParticipationIT.items;

class BudgetIT extends ActivityTestSupport {
    @Test
    void budgetsCoverAccessibleAndOwnActivityGroupsForMoscowMonth() {
        long academic = activity("ACADEMIC", START, 300, group);
        long foreign = create("ACADEMIC", START, otherGroup, 100, grantOtherGroup(), 201).path("id").asLong();
        clock.set(START);
        confirm(academic, items(student, 100));
        confirm(foreign, items(outsider, 50));
        update("delete from teacher_grant where teacher_id=?", teacher);

        var budgets = request(get("/api/v1/budgets"), teacherSession, 200);
        assertThat(budgets.path("month").asText()).isEqualTo("2026-11");
        assertThat(groupIds(budgets.path("items"))).containsExactly(group, otherGroup, secondGroup);
        assertBudget(budgets.path("items").get(0), 1000, 300, 700);
        assertBudget(budgets.path("items").get(1), 1000, 50, 950);
        assertBudget(budgets.path("items").get(2), 1000, 0, 1000);
        var first = budgets.path("items").get(0).path("group");
        assertThat(first.path("name").asText()).isEqualTo("A");
        assertThat(first.path("faculty").path("name").asText()).isEqualTo("Факультет");

        var october = request(get("/api/v1/budgets?month=2026-10"), teacherSession, 200);
        assertThat(october.path("month").asText()).isEqualTo("2026-10");
        assertBudget(october.path("items").get(0), 1000, 0, 1000);
        var others = request(get("/api/v1/budgets"), otherTeacherSession, 200);
        assertThat(groupIds(others.path("items"))).containsExactly(group, secondGroup);
        assertBudget(others.path("items").get(0), 1000, 0, 1000);

        update("insert into settings(key,value,type) values ('monthlyBudgetLimit','200','INTEGER')");
        assertBudget(request(get("/api/v1/budgets"), teacherSession, 200).path("items").get(0), 200, 300, 0);

        clock.set(Instant.parse("2026-11-30T21:00:00Z"));
        assertThat(request(get("/api/v1/budgets"), teacherSession, 200).path("month").asText()).isEqualTo("2026-12");
        for (var month : List.of("2026-13", "2026-1", "november")) {
            var invalid = request(get("/api/v1/budgets?month=" + month), teacherSession, 400);
            assertThat(invalid.path("fieldErrors").get(0).path("field").asText()).isEqualTo("month");
        }
        request(get("/api/v1/budgets"), studentSession, 403);
    }

    @Test
    void awardHistoryIsOwnNewestFirstFilteredAndPagedByCursor() {
        long first = activity("ACADEMIC", START, 100, group, secondGroup);
        long second = activity("ACADEMIC", START, 100, group);
        long foreign = create("ACADEMIC", START, group, 100, otherTeacherSession, 201).path("id").asLong();
        clock.set(START);
        confirm(first, items(student, 100, neighbour, 50));
        clock.set(START.plusSeconds(3600));
        confirm(second, items(classmate, 25));
        request(post(path(foreign) + "/participation").content(JSON.writeValueAsString(items(student, 25))), otherTeacherSession, 200);

        var page = request(get("/api/v1/me/awards?limit=2"), teacherSession, 200);
        assertThat(studentIds(page.path("items"))).containsExactly(classmate, neighbour);
        var latest = page.path("items").get(0);
        assertThat(latest.path("activity").path("id").asLong()).isEqualTo(second);
        assertThat(latest.path("activity").path("title").asText()).isEqualTo("Activity");
        assertThat(latest.path("activity").path("type").asText()).isEqualTo("ACADEMIC");
        assertThat(latest.path("student").path("fullName").asText()).isEqualTo("classmate");
        assertThat(latest.path("group").path("id").asLong()).isEqualTo(group);
        assertThat(latest.path("group").path("faculty").path("code").asText()).isEqualTo(prefix);
        assertThat(latest.path("level").asInt()).isEqualTo(25);
        assertThat(latest.path("amount").asInt()).isEqualTo(25);
        assertThat(latest.path("currency").asText()).isEqualTo("BUA");
        assertThat(latest.path("confirmedAt").asText()).isEqualTo(START.plusSeconds(3600).toString());
        String cursor = page.path("nextCursor").asText();
        var next = request(get("/api/v1/me/awards?limit=2&cursor=" + encode(cursor)), teacherSession, 200);
        assertThat(studentIds(next.path("items"))).containsExactly(student);
        assertThat(next.path("nextCursor").isNull()).isTrue();

        var byGroup = request(get("/api/v1/me/awards?groupId=" + secondGroup), teacherSession, 200);
        assertThat(studentIds(byGroup.path("items"))).containsExactly(neighbour);
        assertThat(request(get("/api/v1/me/awards?month=2026-10"), teacherSession, 200).path("items")).isEmpty();
        assertThat(request(get("/api/v1/me/awards?month=2026-11"), teacherSession, 200).path("items")).hasSize(3);
        assertThat(request(get("/api/v1/me/awards?groupId=" + otherGroup), teacherSession, 200).path("items")).isEmpty();
        assertThat(studentIds(request(get("/api/v1/me/awards"), otherTeacherSession, 200).path("items"))).containsExactly(student);

        for (var query : List.of("groupId=" + group + "&cursor=" + encode(cursor), "month=2026-10&cursor=" + encode(cursor), "cursor=%21%21",
                "cursor=" + encode("bm90LWEtY3Vyc29y"))) {
            var invalid = request(get("/api/v1/me/awards?" + query), teacherSession, 400);
            assertThat(invalid.path("fieldErrors").get(0).path("field").asText()).isEqualTo("cursor");
        }
        request(get("/api/v1/me/awards?limit=0"), teacherSession, 400);
        request(get("/api/v1/me/awards?limit=101"), teacherSession, 400);
        update("delete from teacher_approval where teacher_id=?", teacher);
        assertThat(request(get("/api/v1/me/awards"), teacherSession, 200).path("items")).hasSize(3);
    }

    @Test
    void summaryByGroupAndCurrencyEqualsBudgetSpent() {
        long academic = activity("ACADEMIC", START, 100, group, secondGroup);
        long social = activity("SOCIAL", START, 100, group);
        request(put(path(social) + "/enrollment"), studentSession, 200);
        clock.set(START);
        confirm(academic, items(student, 100, classmate, 50, neighbour, 25));
        confirm(social, items(student, 75));

        var summary = request(get("/api/v1/me/awards/summary"), teacherSession, 200);
        assertThat(summary.path("month").asText()).isEqualTo("2026-11");
        assertThat(summary.path("total").asInt()).isEqualTo(250);
        assertThat(summary.path("count").asInt()).isEqualTo(4);
        var lines = summary.path("items");
        assertThat(lines).hasSize(3);
        assertSummary(lines.get(0), group, "BOA", 75, 1);
        assertSummary(lines.get(1), group, "BUA", 150, 2);
        assertSummary(lines.get(2), secondGroup, "BUA", 25, 1);
        assertThat(lines.get(0).path("group").path("name").asText()).isEqualTo("A");

        var budgets = request(get("/api/v1/budgets"), teacherSession, 200).path("items");
        for (var line : budgets) {
            long id = line.path("group").path("id").asLong();
            int awarded = 0;
            for (var summaryLine : lines) {
                if (summaryLine.path("group").path("id").asLong() == id) {
                    awarded += summaryLine.path("amount").asInt();
                }
            }
            assertThat(line.path("spent").asInt()).isEqualTo(awarded);
        }
        var october = request(get("/api/v1/me/awards/summary?month=2026-10"), teacherSession, 200);
        assertThat(october.path("total").asInt()).isZero();
        assertThat(october.path("count").asInt()).isZero();
        assertThat(october.path("items")).isEmpty();
        assertThat(request(get("/api/v1/me/awards/summary"), otherTeacherSession, 200).path("items")).isEmpty();
        request(get("/api/v1/me/awards/summary?month=2026-00"), teacherSession, 400);
    }

    private void confirm(long activity, Object body) {
        request(post(path(activity) + "/participation").content(JSON.writeValueAsString(body)), teacherSession, 200);
    }

    private void assertBudget(JsonNode budget, int limit, int spent, int remaining) {
        assertThat(budget.path("limit").asInt()).isEqualTo(limit);
        assertThat(budget.path("spent").asInt()).isEqualTo(spent);
        assertThat(budget.path("remaining").asInt()).isEqualTo(remaining);
    }

    private void assertSummary(JsonNode line, long groupId, String currency, int amount, int count) {
        assertThat(line.path("group").path("id").asLong()).isEqualTo(groupId);
        assertThat(line.path("currency").asText()).isEqualTo(currency);
        assertThat(line.path("amount").asInt()).isEqualTo(amount);
        assertThat(line.path("count").asInt()).isEqualTo(count);
    }

    private List<Long> groupIds(JsonNode items) {
        var ids = new ArrayList<Long>();
        items.forEach(item -> ids.add(item.path("group").path("id").asLong()));
        return ids;
    }

    private List<Long> studentIds(JsonNode items) {
        var ids = new ArrayList<Long>();
        items.forEach(item -> ids.add(item.path("student").path("id").asLong()));
        return ids;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

package ru.campusrating.activity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.campusrating.ledger.Author;
import ru.campusrating.ledger.Currency;
import ru.campusrating.ledger.EntryKind;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.ledger.Source;
import ru.campusrating.ledger.SourceType;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class ParticipationIT extends ActivityTestSupport {
    private static final LocalDate NOVEMBER = LocalDate.parse("2026-11-01");
    private static final LocalDate OCTOBER = LocalDate.parse("2026-10-01");

    @Autowired
    LedgerApi ledger;

    @Test
    void participantsAreEnrolledForSocialAndActiveTargetStudentsForAcademic() {
        long social = activity("SOCIAL", START, 100, group, secondGroup);
        long academic = activity("ACADEMIC", START, 100, group, secondGroup);
        request(put(path(social) + "/enrollment"), studentSession, 200);
        request(put(path(social) + "/enrollment"), login("neighbour"), 200);
        clock.set(START);
        confirm(social, items(student, 75), teacherSession, 200);

        var list = request(get(path(social) + "/participants"), teacherSession, 200);
        assertThat(list.path("activityId").asLong()).isEqualTo(social);
        assertThat(list.path("type").asText()).isEqualTo("SOCIAL");
        assertThat(list.path("maxPoints").asInt()).isEqualTo(100);
        assertThat(studentIds(list.path("items"))).containsExactly(student, neighbour);
        var first = list.path("items").get(0);
        assertThat(first.path("student").path("fullName").asText()).isEqualTo("student student");
        assertThat(first.path("group").path("id").asLong()).isEqualTo(group);
        assertThat(first.path("group").path("faculty").path("name").asText()).isEqualTo("Факультет");
        assertThat(first.path("enrolled").asBoolean()).isTrue();
        assertThat(first.path("confirmed").path("level").asInt()).isEqualTo(75);
        assertThat(first.path("confirmed").path("amount").asInt()).isEqualTo(75);
        assertThat(first.path("confirmed").path("confirmedAt").asText()).isEqualTo(START.toString());
        var second = list.path("items").get(1);
        assertThat(second.path("group").path("id").asLong()).isEqualTo(secondGroup);
        assertThat(second.path("confirmed").isNull()).isTrue();

        var academicList = request(get(path(academic) + "/participants"), teacherSession, 200);
        assertThat(academicList.path("type").asText()).isEqualTo("ACADEMIC");
        assertThat(studentIds(academicList.path("items"))).containsExactly(classmate, student, neighbour);
        assertThat(academicList.path("items").get(0).path("enrolled").isNull()).isTrue();
        assertThat(academicList.path("items").get(0).path("confirmed").isNull()).isTrue();
    }

    @Test
    void onlyAuthorReadsAndConfirmsEvenAfterLosingGroupAccess() {
        long academic = activity("ACADEMIC", START, 100, group);
        clock.set(START);
        problem(request(get(path(academic) + "/participants"), otherTeacherSession, 403), "FORBIDDEN");
        problem(preview(academic, items(student, 50), otherTeacherSession, 403), "FORBIDDEN");
        problem(confirm(academic, items(student, 50), otherTeacherSession, 403), "FORBIDDEN");
        request(get(path(academic) + "/participants"), studentSession, 403);
        problem(request(get(path(Long.MAX_VALUE) + "/participants"), teacherSession, 404), "NOT_FOUND");
        problem(confirm(Long.MAX_VALUE, items(student, 50), teacherSession, 404), "NOT_FOUND");
        update("delete from teacher_approval where teacher_id=?", teacher);
        assertThat(request(get(path(academic) + "/participants"), teacherSession, 200).path("items")).hasSize(2);
        assertThat(confirm(academic, items(student, 50), teacherSession, 200).path("total").asInt()).isEqualTo(50);
        assertThat(count("select count(*) from activity_award where activity_id=?", academic)).isEqualTo(1);
    }

    @Test
    void requestShapeAndStartTimeAreChecked() {
        long academic = activity("ACADEMIC", START, 100, group);
        clock.set(START.minusSeconds(1));
        problem(confirm(academic, items(student, 50), teacherSession, 409), "INVALID_STATE");
        problem(preview(academic, items(student, 50), teacherSession, 409), "INVALID_STATE");
        clock.set(START);
        var duplicate = confirm(academic, items(student, 50, student, 75), teacherSession, 400);
        problem(duplicate, "VALIDATION_FAILED");
        assertThat(duplicate.path("fieldErrors").get(0).path("field").asText()).isEqualTo("items");
        problem(preview(academic, items(student, 50, student, 50), teacherSession, 400), "VALIDATION_FAILED");
        var level = confirm(academic, items(student, 30), teacherSession, 400);
        assertThat(level.path("fieldErrors").get(0).path("field").asText()).isEqualTo("items[0].level");
        problem(confirm(academic, Map.of("items", List.of()), teacherSession, 400), "VALIDATION_FAILED");
        problem(confirm(academic, items(0, 50), teacherSession, 400), "VALIDATION_FAILED");
        assertThat(count("select count(*) from activity_award where activity_id=?", academic)).isZero();
    }

    @Test
    void enrollmentAndEligibilityRejectWholeRequest() {
        long social = activity("SOCIAL", START, 100, group);
        long academic = activity("ACADEMIC", START, 100, group);
        request(put(path(social) + "/enrollment"), studentSession, 200);
        clock.set(START);
        var notEnrolled = confirm(social, items(student, 50, classmate, 50), teacherSession, 422);
        problem(notEnrolled, "NOT_ENROLLED");
        assertThat(longs(notEnrolled.path("studentIds"))).containsExactly(classmate);
        problem(preview(social, items(classmate, 50), teacherSession, 422), "NOT_ENROLLED");
        var notEligible = confirm(social, items(classmate, 50, pending, 50, outsider, 50, teacher, 50), teacherSession, 422);
        problem(notEligible, "STUDENT_NOT_ELIGIBLE");
        assertThat(longs(notEligible.path("studentIds"))).containsExactly(pending, outsider, teacher);
        problem(preview(academic, items(pending, 50), teacherSession, 422), "STUDENT_NOT_ELIGIBLE");
        problem(confirm(academic, items(student, 50, outsider, 50), teacherSession, 422), "STUDENT_NOT_ELIGIBLE");
        assertThat(count("select count(*) from activity_award where author_id=?", teacher)).isZero();
        assertThat(count("select count(*) from activity_budget where teacher_id=?", teacher)).isZero();
        assertThat(confirm(academic, items(classmate, 50), teacherSession, 200).path("awards")).hasSize(1);
    }

    @Test
    void confirmationPostsAwardsSpendsBudgetAndKeepsLedgerConsistent() {
        long academic = activity("ACADEMIC", START, 75, group, secondGroup);
        clock.set(START.plusSeconds(60));
        var result = confirm(academic, items(neighbour, 100, student, 50), teacherSession, 200);
        assertThat(result.path("currency").asText()).isEqualTo("BUA");
        assertThat(result.path("total").asInt()).isEqualTo(113);
        assertThat(result.path("alreadyConfirmed")).isEmpty();
        assertThat(studentIdsOf(result.path("awards"))).containsExactly(student, neighbour);
        assertLine(result.path("awards").get(0), student, group, 50, 38);
        assertLine(result.path("awards").get(1), neighbour, secondGroup, 100, 75);
        assertThat(spent(group, NOVEMBER)).isEqualTo(38);
        assertThat(spent(secondGroup, NOVEMBER)).isEqualTo(75);
        assertThat(count("""
                select count(*) from activity_award w join ledger_entry e on e.id=w.ledger_entry_id
                where w.activity_id=? and e.idempotency_key='award:' || w.activity_id || ':' || w.student_id
                    and e.amount=w.amount and e.currency=w.currency
                """, academic)).isEqualTo(2);
        var history = ledger.history(student, Optional.empty(), Optional.empty(), 10).items();
        assertThat(history).hasSize(1);
        var entry = history.getFirst();
        assertThat(entry.currency()).isEqualTo(Currency.BUA);
        assertThat(entry.amount()).isEqualTo(38);
        assertThat(entry.kind()).isEqualTo(EntryKind.ACTIVITY_AWARD);
        assertThat(entry.source()).isEqualTo(new Source(SourceType.ACTIVITY, academic, "Activity"));
        assertThat(entry.author()).isEqualTo(new Author(teacher, "teacher teacher"));
        assertThat(ledger.balances(neighbour).bua()).isEqualTo(75);
        assertThat(ledger.reconcile().mismatches()).noneMatch(mismatch -> students().contains(mismatch.studentId()));
        assertThat(request(get(path(academic)), teacherSession, 200).path("confirmedCount").asInt()).isEqualTo(2);
        assertThat(request(get(path(academic)), studentSession, 200).path("myAward").path("amount").asInt()).isEqualTo(38);
    }

    @Test
    void repeatWithSameLevelIsAlreadyConfirmedAndOtherLevelConflicts() {
        long academic = activity("ACADEMIC", START, 100, group);
        clock.set(START);
        confirm(academic, items(student, 50), teacherSession, 200);
        var repeat = confirm(academic, items(student, 50), teacherSession, 200);
        assertThat(repeat.path("awards")).isEmpty();
        assertThat(repeat.path("total").asInt()).isZero();
        assertLine(repeat.path("alreadyConfirmed").get(0), student, group, 50, 50);
        assertThat(spent(group, NOVEMBER)).isEqualTo(50);
        assertThat(awardEntries(academic)).isEqualTo(1);
        assertThat(ledger.balances(student).bua()).isEqualTo(50);

        var mixed = confirm(academic, items(classmate, 100, student, 50), teacherSession, 200);
        assertThat(studentIdsOf(mixed.path("awards"))).containsExactly(classmate);
        assertThat(studentIdsOf(mixed.path("alreadyConfirmed"))).containsExactly(student);
        assertThat(mixed.path("total").asInt()).isEqualTo(100);
        assertThat(spent(group, NOVEMBER)).isEqualTo(150);

        var conflict = confirm(academic, items(student, 75, classmate, 25, outsider, 50), teacherSession, 409);
        problem(conflict, "ALREADY_CONFIRMED");
        assertThat(longs(conflict.path("studentIds"))).containsExactly(student, classmate);
        problem(preview(academic, items(student, 75), teacherSession, 409), "ALREADY_CONFIRMED");

        update("update group_membership set state='PENDING',activated_at=null where student_id=?", student);
        var afterMembershipLoss = preview(academic, items(student, 50), teacherSession, 200);
        assertThat(studentIdsOf(afterMembershipLoss.path("alreadyConfirmed"))).containsExactly(student);
        assertThat(afterMembershipLoss.path("budgets")).isEmpty();
        assertThat(afterMembershipLoss.path("withinBudget").asBoolean()).isTrue();
        var participants = request(get(path(academic) + "/participants"), teacherSession, 200);
        assertThat(studentIds(participants.path("items"))).doesNotContain(student).contains(classmate);
        assertThat(spent(group, NOVEMBER)).isEqualTo(150);
        assertThat(awardEntries(academic)).isEqualTo(2);
    }

    @Test
    void budgetShortfallInOneGroupRejectsEverythingAndPreviewShowsIt() {
        long first = activity("ACADEMIC", START, 950, group, secondGroup);
        long social = activity("SOCIAL", START, 100, group, secondGroup);
        long tiny = activity("ACADEMIC", START, 4, group);
        request(put(path(social) + "/enrollment"), studentSession, 200);
        request(put(path(social) + "/enrollment"), login("classmate"), 200);
        request(put(path(social) + "/enrollment"), login("neighbour"), 200);
        clock.set(START);
        confirm(first, items(student, 100), teacherSession, 200);
        assertThat(spent(group, NOVEMBER)).isEqualTo(950);

        var body = items(student, 50, classmate, 50, neighbour, 50);
        var preview = preview(social, body, teacherSession, 200);
        assertThat(preview.path("currency").asText()).isEqualTo("BOA");
        assertThat(preview.path("total").asInt()).isEqualTo(150);
        assertThat(preview.path("withinBudget").asBoolean()).isFalse();
        assertBudget(preview.path("budgets").get(0), group, "A", 100, 50, 50);
        assertBudget(preview.path("budgets").get(1), secondGroup, "C", 50, 1000, 0);
        assertThat(count("select count(*) from activity_budget where teacher_id=? and group_id=?", teacher, secondGroup)).isZero();

        var rejected = confirm(social, body, teacherSession, 422);
        problem(rejected, "BUDGET_EXCEEDED");
        assertThat(rejected.path("budgets")).hasSize(1);
        assertBudget(rejected.path("budgets").get(0), group, "A", 100, 50, 50);
        assertThat(count("select count(*) from activity_award where activity_id=?", social)).isZero();
        assertThat(awardEntries(social)).isZero();
        assertThat(spent(group, NOVEMBER)).isEqualTo(950);
        assertThat(spent(secondGroup, NOVEMBER)).isZero();

        assertThat(confirm(social, items(classmate, 50), teacherSession, 200).path("total").asInt()).isEqualTo(50);
        assertThat(spent(group, NOVEMBER)).isEqualTo(1000);
        var overByOne = confirm(tiny, items(classmate, 25), teacherSession, 422);
        assertBudget(overByOne.path("budgets").get(0), group, "A", 1, 0, 1);
        assertThat(ledger.balances(classmate).boa()).isEqualTo(50);
        assertThat(ledger.balances(classmate).bua()).isZero();
    }

    @Test
    void limitIsReadOnEveryCheckAndRemainingNeverGoesNegative() {
        long academic = activity("ACADEMIC", START, 300, group);
        clock.set(START);
        confirm(academic, items(student, 100), teacherSession, 200);
        update("insert into settings(key,value,type) values ('monthlyBudgetLimit','200','INTEGER')");
        var preview = preview(academic, items(classmate, 25), teacherSession, 200);
        assertBudget(preview.path("budgets").get(0), group, "A", 75, 0, 75);
        problem(confirm(academic, items(classmate, 25), teacherSession, 422), "BUDGET_EXCEEDED");
        update("update settings set value='400' where key='monthlyBudgetLimit'");
        assertThat(confirm(academic, items(classmate, 25), teacherSession, 200).path("total").asInt()).isEqualTo(75);
        assertThat(spent(group, NOVEMBER)).isEqualTo(375);
    }

    @Test
    void monthBoundaryFollowsMoscowTime() {
        clock.set(Instant.parse("2026-10-30T09:00:00Z"));
        Instant start = Instant.parse("2026-10-31T20:00:00Z");
        long october = activity("ACADEMIC", start, 1000, group);
        long november = activity("ACADEMIC", start, 100, group);
        clock.set(Instant.parse("2026-10-31T20:59:59.999999900Z"));
        confirm(october, items(student, 100), teacherSession, 200);
        assertThat(spent(group, OCTOBER)).isEqualTo(1000);
        assertThat(request(get("/api/v1/me/awards/summary?month=2026-10"), teacherSession, 200).path("total").asInt()).isEqualTo(1000);
        assertThat(preview(november, items(classmate, 100), teacherSession, 200).path("withinBudget").asBoolean()).isFalse();
        clock.set(Instant.parse("2026-10-31T21:00:00Z"));
        var preview = preview(november, items(classmate, 100), teacherSession, 200);
        assertBudget(preview.path("budgets").get(0), group, "A", 100, 1000, 0);
        confirm(november, items(classmate, 100), teacherSession, 200);
        assertThat(spent(group, OCTOBER)).isEqualTo(1000);
        assertThat(spent(group, NOVEMBER)).isEqualTo(100);
    }

    @Test
    void parallelIdenticalConfirmationsPostOnceAndSpendOnce() throws Exception {
        long academic = activity("ACADEMIC", START, 100, group);
        clock.set(START);
        var responses = confirmInParallel(academic, items(student, 100, classmate, 50), academic, items(student, 100, classmate, 50));
        assertThat(responses).allSatisfy(response -> assertThat(response.status()).withFailMessage(response.body()).isEqualTo(200));
        var awarded = responses.stream().mapToInt(response -> JSON.readTree(response.body()).path("awards").size()).sorted().toArray();
        assertThat(awarded).containsExactly(0, 2);
        assertThat(awardEntries(academic)).isEqualTo(2);
        assertThat(spent(group, NOVEMBER)).isEqualTo(150);
        assertThat(ledger.balances(student).bua()).isEqualTo(100);
    }

    @Test
    void parallelConfirmationsOfDifferentActivitiesShareBudgetWithoutDeadlock() throws Exception {
        long firstRound = activity("ACADEMIC", START, 300, group, secondGroup);
        long secondRound = activity("ACADEMIC", START, 300, group, secondGroup);
        long thirdRound = activity("ACADEMIC", START, 300, group, secondGroup);
        long fourthRound = activity("ACADEMIC", START, 300, group, secondGroup);
        clock.set(START);
        var both = confirmInParallel(firstRound, items(student, 100, neighbour, 25), secondRound, items(classmate, 100, neighbour, 25));
        assertThat(both).extracting(Response::status).containsExactly(200, 200);
        assertThat(spent(group, NOVEMBER)).isEqualTo(600);
        assertThat(spent(secondGroup, NOVEMBER)).isEqualTo(150);

        var one = confirmInParallel(thirdRound, items(neighbour, 25, student, 100), fourthRound, items(classmate, 100, neighbour, 25));
        assertThat(one).extracting(Response::status).containsExactlyInAnyOrder(200, 422);
        var rejected = one.stream().filter(response -> response.status() == 422).findFirst().orElseThrow();
        problem(JSON.readTree(rejected.body()), "BUDGET_EXCEEDED");
        assertThat(spent(group, NOVEMBER)).isEqualTo(900);
        assertThat(spent(secondGroup, NOVEMBER)).isEqualTo(225);
        assertThat(ledger.reconcile().mismatches()).noneMatch(mismatch -> students().contains(mismatch.studentId()));
    }

    private List<Response> confirmInParallel(long first, Map<String, Object> firstBody, long second, Map<String, Object> secondBody)
            throws Exception {
        var firstSession = login("teacher");
        var secondSession = login("teacher");
        return parallel(
                () -> exchange(post(path(first) + "/participation").content(JSON.writeValueAsString(firstBody)), firstSession),
                () -> exchange(post(path(second) + "/participation").content(JSON.writeValueAsString(secondBody)), secondSession));
    }

    @SafeVarargs
    private List<Response> parallel(Supplier<Response>... calls) throws Exception {
        var barrier = new CyclicBarrier(calls.length);
        ExecutorService executor = Executors.newFixedThreadPool(calls.length);
        try {
            var futures = new ArrayList<Future<Response>>();
            for (var call : calls) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return call.get();
                }));
            }
            var responses = new ArrayList<Response>();
            for (var future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            executor.shutdownNow();
        }
    }

    private JsonNode confirm(long activity, Map<String, Object> body, Browser session, int status) {
        return request(post(path(activity) + "/participation").content(JSON.writeValueAsString(body)), session, status);
    }

    private JsonNode preview(long activity, Map<String, Object> body, Browser session, int status) {
        return request(post(path(activity) + "/participation/preview").content(JSON.writeValueAsString(body)), session, status);
    }

    static Map<String, Object> items(long... pairs) {
        var items = new ArrayList<Map<String, Long>>();
        for (int index = 0; index < pairs.length; index += 2) {
            items.add(Map.of("studentId", pairs[index], "level", pairs[index + 1]));
        }
        return Map.of("items", items);
    }

    private int spent(long target, LocalDate month) {
        return count("select coalesce(sum(spent),0) from activity_budget where teacher_id=? and group_id=? and month=?",
                teacher, target, month);
    }

    private int awardEntries(long activity) {
        return count("select count(*) from ledger_entry where idempotency_key like ?", "award:" + activity + ":%");
    }

    private void assertLine(JsonNode line, long studentId, long groupId, int level, int amount) {
        assertThat(line.path("studentId").asLong()).isEqualTo(studentId);
        assertThat(line.path("groupId").asLong()).isEqualTo(groupId);
        assertThat(line.path("level").asInt()).isEqualTo(level);
        assertThat(line.path("amount").asInt()).isEqualTo(amount);
    }

    private void assertBudget(JsonNode budget, long groupId, String name, int needed, int remaining, int shortfall) {
        assertThat(budget.path("groupId").asLong()).isEqualTo(groupId);
        assertThat(budget.path("groupName").asText()).isEqualTo(name);
        assertThat(budget.path("needed").asInt()).isEqualTo(needed);
        assertThat(budget.path("remaining").asInt()).isEqualTo(remaining);
        assertThat(budget.path("shortfall").asInt()).isEqualTo(shortfall);
    }

    private List<Long> studentIds(JsonNode participants) {
        var ids = new ArrayList<Long>();
        participants.forEach(item -> ids.add(item.path("student").path("id").asLong()));
        return ids;
    }

    private List<Long> studentIdsOf(JsonNode lines) {
        var ids = new ArrayList<Long>();
        lines.forEach(line -> ids.add(line.path("studentId").asLong()));
        return ids;
    }

    private List<Long> longs(JsonNode values) {
        var ids = new ArrayList<Long>();
        values.forEach(value -> ids.add(value.asLong()));
        return ids;
    }
}

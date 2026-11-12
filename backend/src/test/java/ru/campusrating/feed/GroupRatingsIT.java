package ru.campusrating.feed;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.campusrating.ledger.Currency;
import ru.campusrating.ledger.EntryKind;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class GroupRatingsIT extends FeedTestSupport {
    @ParameterizedTest
    @ValueSource(strings = {"AR", "BUA", "BOA"})
    void sortsBySelectedCurrencyThenCurrentNameThenIdAndExcludesPending(String sort) {
        seedBalances();
        update("update app_user set full_name=? where id=?", "Антонов Антон Антонович", student);
        update("update app_user set full_name=? where id=?", "Яковлев Яков Яковлевич", classmate);
        var rating = request(get(path(group) + "?sort=" + sort), login("teacher"), 200);
        assertGroup(rating.path("group"), group, "Основная", faculty, prefix, "Факультет ленты");
        assertThat(rating.path("sort").asText()).isEqualTo(sort);
        List<Long> expected = switch (sort) {
            case "AR" -> List.of(twinFirst, twinSecond, classmate, student, zero);
            case "BUA" -> List.of(student, twinFirst, twinSecond, classmate, zero);
            case "BOA" -> List.of(twinFirst, twinSecond, classmate, student, zero);
            default -> throw new IllegalArgumentException();
        };
        assertThat(studentIds(rating)).containsExactlyElementsOf(expected);
        for (var row : rating.path("items")) {
            long target = row.path("student").path("id").asLong();
            String name = target == student ? "Антонов Антон Антонович"
                    : target == classmate ? "Яковлев Яков Яковлевич"
                    : target == zero ? "Нулевой Николай Николаевич" : "Белов Борис Борисович";
            userRef(row.path("student"), target, name);
            assertSnapshot(row, target);
        }
    }

    @Test
    void omittedSortDefaultsToAr() {
        seedBalances();
        var result = request(get(path(group)), login("teacher"), 200);
        assertThat(result.path("sort").asText()).isEqualTo("AR");
        assertThat(studentIds(result)).containsExactly(classmate, twinFirst, twinSecond, student, zero);
    }

    @Test
    void inaccessibleGroupIsForbidden() {
        problem(request(get(path(otherGroup)), login("teacher"), 403), 403, "GROUP_NOT_ACCESSIBLE");
    }

    @Test
    void explicitGrantOpensGroupOfAnotherFaculty() {
        award(outsider, Currency.BUA, 17);
        update("insert into teacher_grant(teacher_id,kind,group_id) values (?,'GROUP',?)", teacher, otherGroup);
        var rating = request(get(path(otherGroup)), login("teacher"), 200);
        assertGroup(rating.path("group"), otherGroup, "Чужая", otherFaculty, prefix + "other", "Другой факультет");
        assertThat(rating.path("sort").asText()).isEqualTo("AR");
        assertThat(studentIds(rating)).containsExactly(outsider);
        userRef(rating.path("items").get(0).path("student"), outsider, "Чужой Человек");
        assertBalances(rating.path("items").get(0).path("balances"), 0, 17, 0);
    }

    @Test
    void unknownGroupIsNotFoundBeforeScopeCheck() {
        problem(request(get(path(Long.MAX_VALUE)), login("teacher"), 404), 404, "NOT_FOUND");
    }

    @Test
    void existingGroupWithoutActiveStudentsReturnsMetadataAndEmptyItems() {
        var rating = request(get(path(emptyGroup)), login("teacher"), 200);
        assertGroup(rating.path("group"), emptyGroup, "Пустая", faculty, prefix, "Факультет ленты");
        assertThat(rating.path("sort").asText()).isEqualTo("AR");
        assertThat(rating.path("items").isArray()).isTrue();
        assertThat(rating.path("items")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"student", "admin"})
    void studentAndAdminCannotReadRating(String role) {
        problem(request(get(path(group)), login(role), 403), 403, "FORBIDDEN");
    }

    @Test
    void unapprovedTeacherCannotReadRating() {
        problem(request(get(path(group)), login("unapproved"), 403), 403, "GROUP_NOT_ACCESSIBLE");
    }

    @Test
    void guestCannotReadRating() {
        problem(request(get(path(group)), null, 401), 401, "UNAUTHENTICATED");
    }

    @Test
    void unknownSortIsValidationFailure() {
        validation(request(get(path(group) + "?sort=OTHER"), login("teacher"), 400), "sort");
    }

    @Test
    void ratingReadsBalanceSnapshotAndIgnoresExtraJournalEntry() {
        seedBalances();
        var session = login("teacher");
        var before = request(get(path(group) + "?sort=BUA"), session, 200);
        assertThat(studentIds(before)).containsExactly(student, classmate, twinFirst, twinSecond, zero);
        before.path("items").forEach(row -> assertSnapshot(row, row.path("student").path("id").asLong()));
        long extra = id("""
                insert into ledger_entry(student_id,currency,kind,amount,source_type,source_id,source_title,
                author_id,author_name,idempotency_key,created_at)
                values (?,'BUA','ACTIVITY_AWARD',777,'ACTIVITY',?,?,?,?,?,?) returning id
                """, classmate, ACTIVITY_ID, ACTIVITY_TITLE, teacher, AUTHOR_NAME, prefix + "extra", clock.instant());
        assertThat(extra).isPositive();
        assertThat(jdbc.queryForObject("select sum(amount) from ledger_entry where student_id=? and currency='BUA'",
                Integer.class, classmate)).isEqualTo(827);
        assertThat(jdbc.queryForObject("select amount from ledger_balance where student_id=? and currency='BUA'",
                Integer.class, classmate)).isEqualTo(50);

        var after = request(get(path(group) + "?sort=BUA"), session, 200);

        assertThat(after).isEqualTo(before);
        after.path("items").forEach(row -> assertSnapshot(row, row.path("student").path("id").asLong()));
    }

    private void seedBalances() {
        ar(student, EntryKind.INITIAL, 120);
        ar(student, EntryKind.PENALTY, -20);
        ar(classmate, EntryKind.SEMESTER_ACCRUAL, 200);
        ar(twinFirst, EntryKind.INITIAL, 200);
        ar(twinSecond, EntryKind.INITIAL, 200);
        award(student, Currency.BUA, 90);
        award(student, Currency.BOA, 10);
        for (long target : List.of(classmate, twinFirst, twinSecond)) {
            award(target, Currency.BUA, 50);
            award(target, Currency.BOA, 90);
        }
        ar(pending, EntryKind.INITIAL, 1000);
        award(pending, Currency.BUA, 1000);
        award(pending, Currency.BOA, 1000);
        ar(outsider, EntryKind.INITIAL, 1000);
    }

    private String path(long target) {
        return "/api/v1/groups/" + target + "/ratings";
    }

    private List<Long> studentIds(JsonNode rating) {
        var result = new ArrayList<Long>();
        rating.path("items").forEach(row -> result.add(row.path("student").path("id").asLong()));
        return result;
    }

    private void assertGroup(JsonNode result, long target, String name, long facultyId, String code, String facultyName) {
        assertThat(result.path("id").asLong()).isEqualTo(target);
        assertThat(result.path("name").asText()).isEqualTo(name);
        assertThat(result.path("faculty").path("id").asLong()).isEqualTo(facultyId);
        assertThat(result.path("faculty").path("code").asText()).isEqualTo(code);
        assertThat(result.path("faculty").path("name").asText()).isEqualTo(facultyName);
    }

    private void assertSnapshot(JsonNode row, long target) {
        int[] expected = target == student ? new int[]{100, 90, 10}
                : target == zero ? new int[]{0, 0, 0} : new int[]{200, 50, 90};
        assertBalances(row.path("balances"), expected[0], expected[1], expected[2]);
        for (var currency : Currency.values()) {
            Integer amount = jdbc.queryForObject("select coalesce((select amount from ledger_balance where student_id=? and currency=?),0)",
                    Integer.class, target, currency.name());
            assertThat(row.path("balances").path(currency.name()).asInt()).isEqualTo(amount);
        }
    }

    private void assertBalances(JsonNode result, int ar, int bua, int boa) {
        assertThat(result.has("AR")).isTrue();
        assertThat(result.has("BUA")).isTrue();
        assertThat(result.has("BOA")).isTrue();
        assertThat(result.path("AR").asInt()).isEqualTo(ar);
        assertThat(result.path("BUA").asInt()).isEqualTo(bua);
        assertThat(result.path("BOA").asInt()).isEqualTo(boa);
    }
}

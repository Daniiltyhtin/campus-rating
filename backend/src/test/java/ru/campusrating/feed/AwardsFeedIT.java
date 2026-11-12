package ru.campusrating.feed;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.campusrating.ledger.Currency;
import ru.campusrating.ledger.EntryKind;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class AwardsFeedIT extends FeedTestSupport {
    @Test
    void newestAwardComesFirstAndSystemEntriesAreExcluded() {
        Instant oldTime = clock.instant();
        long old = award(student, Currency.BUA, 38);
        clock.set(clock.instant().plusSeconds(1));
        Instant recentTime = clock.instant();
        long recent = award(outsider, Currency.BOA, 50);
        long sameTime = award(classmate, Currency.BUA, 75);
        clock.set(clock.instant().plusSeconds(1));
        ar(student, EntryKind.INITIAL, 800);
        ar(student, EntryKind.PENALTY, -20);
        ar(student, EntryKind.SEMESTER_ACCRUAL, 100);

        var page = request(get(AWARDS + "?limit=3"), login("student"), 200);

        assertThat(ids(page.path("items"), "entryId")).containsExactly(sameTime, recent, old);
        assertAward(page.path("items").get(0), sameTime, recentTime, classmate, "Антонов Антон Антонович", 75, "BUA");
        assertAward(page.path("items").get(1), recent, recentTime, outsider, "Чужой Человек", 50, "BOA");
        assertAward(page.path("items").get(2), old, oldTime, student, "Яковлев Яков Яковлевич", 38, "BUA");
        assertThat(page.has("nextCursor")).isTrue();
    }

    @Test
    void cursorPaginatesWithoutDuplicatesAndPreservesAuthorSnapshotWithCurrentStudentName() {
        var expected = new ArrayList<Long>();
        for (int i = 0; i < 23; i++) {
            expected.add(award(i % 2 == 0 ? student : outsider, Currency.BOA, i + 1));
        }
        Collections.reverse(expected);
        update("update app_user set full_name=? where id=?", "Новое ФИО студента", student);
        update("update app_user set full_name=? where id=?", "Текущее ФИО автора", teacher);
        var session = login("student");
        var page = request(get(AWARDS), session, 200);
        assertThat(ids(page.path("items"), "entryId")).containsExactlyElementsOf(expected.subList(0, 20));
        assertThat(page.path("nextCursor").isTextual()).isTrue();
        assertThat(page.path("nextCursor").asText()).isNotBlank();
        clock.set(clock.instant().plusSeconds(1));
        long newlyInserted = award(classmate, Currency.BUA, 99);
        var seen = new ArrayList<Long>();
        int pages = 0;
        while (true) {
            assertThat(++pages).isLessThan(1000);
            assertThat(page.has("nextCursor")).isTrue();
            assertThat(page.path("items").size()).isBetween(1, 20);
            page.path("items").forEach(item -> {
                long entryId = item.path("entryId").asLong();
                seen.add(entryId);
                if (expected.contains(entryId)) {
                    long target = item.path("student").path("id").asLong();
                    userRef(item.path("student"), target,
                            target == student ? "Новое ФИО студента" : "Чужой Человек");
                    userRef(item.path("teacher"), teacher, AUTHOR_NAME);
                    assertThat(item.path("activity").path("title").asText()).isEqualTo(ACTIVITY_TITLE);
                }
            });
            if (page.path("nextCursor").isNull()) {
                break;
            }
            page = request(get(AWARDS + "?cursor=" + encode(page.path("nextCursor").asText())), session, 200);
        }
        assertThat(seen).doesNotHaveDuplicates().doesNotContain(newlyInserted);
        assertThat(seen.stream().filter(expected::contains).toList()).containsExactlyElementsOf(expected);
        assertThat(request(get(AWARDS + "?limit=1"), session, 200).path("items").get(0).path("entryId").asLong())
                .isEqualTo(newlyInserted);
    }

    @Test
    void brokenCursorIdentifiesCursorField() {
        validation(request(get(AWARDS + "?cursor=broken"), login("student"), 400), "cursor");
    }

    @Test
    void ledgerHistoryCursorIsRejectedAsForeignToFeed() {
        ar(student, EntryKind.INITIAL, 100);
        ar(student, EntryKind.SEMESTER_ACCRUAL, 100);
        String cursor = ledger.history(student, Optional.empty(), Optional.empty(), 1).nextCursor();
        assertThat(cursor).isNotBlank();
        validation(request(get(AWARDS + "?cursor=" + encode(cursor)), login("student"), 400), "cursor");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void invalidLimitIsRejected(int limit) {
        validation(request(get(AWARDS + "?limit=" + limit), login("student"), 400), "limit");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void limitBoundariesAreAccepted(int limit) {
        var entries = new ArrayList<Long>();
        for (int i = 0; i <= limit; i++) {
            entries.add(award(student, Currency.BOA, 1));
        }
        Collections.reverse(entries);
        var page = request(get(AWARDS + "?limit=" + limit), login("student"), 200);
        assertThat(ids(page.path("items"), "entryId")).containsExactlyElementsOf(entries.subList(0, limit));
        assertThat(page.path("nextCursor").isTextual()).isTrue();
        assertThat(page.path("nextCursor").asText()).isNotBlank();
    }

    @Test
    void guestCannotReadFeed() {
        problem(request(get(AWARDS), null, 401), 401, "UNAUTHENTICATED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"student", "pending", "teacher", "unapproved", "admin"})
    void everyAuthenticatedRoleCanReadAwardsAcrossFaculties(String role) {
        long entry = award(outsider, Currency.BOA, 12);
        var page = request(get(AWARDS + "?limit=1"), login(role), 200);
        assertThat(ids(page.path("items"), "entryId")).containsExactly(entry);
        userRef(page.path("items").get(0).path("student"), outsider, "Чужой Человек");
    }

    private void assertAward(JsonNode item, long entry, Instant createdAt, long target, String name, int amount, String currency) {
        assertThat(item.path("entryId").asLong()).isEqualTo(entry);
        assertThat(Instant.parse(item.path("createdAt").asText())).isEqualTo(createdAt);
        userRef(item.path("student"), target, name);
        assertThat(item.path("amount").asInt()).isEqualTo(amount);
        assertThat(item.path("currency").asText()).isEqualTo(currency);
        assertThat(item.path("activity").path("id").asLong()).isEqualTo(ACTIVITY_ID);
        assertThat(item.path("activity").path("title").asText()).isEqualTo(ACTIVITY_TITLE);
        userRef(item.path("teacher"), teacher, AUTHOR_NAME);
    }

    private String encode(String cursor) {
        return URLEncoder.encode(cursor, StandardCharsets.UTF_8);
    }
}

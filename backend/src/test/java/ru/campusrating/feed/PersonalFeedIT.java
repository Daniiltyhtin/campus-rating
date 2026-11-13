package ru.campusrating.feed;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import ru.campusrating.activity.ActivityCreated;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class PersonalFeedIT extends PersonalFeedSupport {
    @Test
    void twentyFiveEventsArePagedByTwentyWithoutOverlap() {
        var expected = new ArrayList<Long>();
        for (int index = 0; index < 25; index++) {
            expected.add(row(student, "assignment:" + index, NOW.plusSeconds(index / 2)));
        }
        expected.sort(Collections.reverseOrder());
        var first = request(get(FEED), studentSession, 200);
        assertThat(ids(first)).hasSize(20);
        assertThat(first.path("nextCursor").isTextual()).isTrue();
        var second = request(get(FEED + "?cursor=" + encode(first.path("nextCursor").asText())), studentSession, 200);
        assertThat(ids(second)).hasSize(5);
        assertThat(second.path("nextCursor").isNull()).isTrue();
        var all = new ArrayList<>(ids(first));
        all.addAll(ids(second));
        assertThat(all).doesNotHaveDuplicates().containsExactlyElementsOf(order(expected));
    }

    @Test
    void itemCarriesSnapshotFieldsOfItsKind() {
        long id = row(student, "assignment:1", NOW);
        var item = request(get(FEED), studentSession, 200).path("items").get(0);
        assertThat(item.path("id").asLong()).isEqualTo(id);
        assertThat(item.path("kind").asText()).isEqualTo("ASSIGNMENT_CREATED");
        assertThat(Instant.parse(item.path("createdAt").asText())).isEqualTo(NOW);
        assertThat(item.path("assignmentId").asLong()).isEqualTo(901);
        assertThat(item.path("checkpointTitle").asText()).isEqualTo("Эссе");
        assertThat(Instant.parse(item.path("deadline").asText())).isEqualTo(ESSAY_DEADLINE);
    }

    @Test
    void userSeesOnlyOwnEventsAndAdminGetsEmptyPage() {
        long own = row(student, "assignment:1", NOW);
        row(classmate, "assignment:2", NOW);
        row(admin, "assignment:3", NOW);
        assertThat(ids(request(get(FEED), studentSession, 200))).containsExactly(own);
        var adminPage = request(get(FEED), login("admin"), 200);
        assertThat(adminPage.path("items")).isEmpty();
        assertThat(adminPage.path("nextCursor").isNull()).isTrue();
        assertThat(request(get(FEED), login("pending"), 200).path("items")).isEmpty();
    }

    @Test
    void guestIsRejected() {
        assertThat(request(get(FEED), null, 401).path("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void sameDedupKeyForSameRecipientIsRejectedByDatabase() {
        row(student, "assignment:1", NOW);
        row(classmate, "assignment:1", NOW);
        assertThatThrownBy(() -> row(student, "assignment:1", NOW.plusSeconds(5)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(events(student)).isEqualTo(1);
    }

    @Test
    void brokenOrForeignCursorIsRejected() {
        problem(request(get(FEED + "?cursor=broken"), studentSession, 400), "cursor");
        for (int index = 0; index < 3; index++) {
            row(classmate, "assignment:" + index, NOW);
        }
        String foreign = request(get(FEED + "?limit=1"), login("classmate"), 200).path("nextCursor").asText();
        problem(request(get(FEED + "?cursor=" + encode(foreign)), studentSession, 400), "cursor");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void invalidLimitIsRejected(int limit) {
        problem(request(get(FEED + "?limit=" + limit), studentSession, 400), "limit");
    }

    @Test
    void completedPublicationIsRemovedFromRegistry() {
        activity("Субботник", "SOCIAL", NOW.plus(Duration.ofDays(2)), 5, group);
        await().atMost(Duration.ofSeconds(10)).until(() -> events(student) == 1);
        await().atMost(Duration.ofSeconds(10)).until(() -> count(
                "select count(*) from event_publication where event_type=?", ActivityCreated.class.getName()) == 0);
    }

    private long row(long recipient, String key, Instant at) {
        return id("""
                insert into user_event(recipient_id,kind,dedup_key,created_at,target_id,title,deadline)
                values (?,'ASSIGNMENT_CREATED',?,?,901,'Эссе',?) returning id
                """, recipient, key, at, ESSAY_DEADLINE);
    }

    private List<Long> ids(JsonNode page) {
        var ids = new ArrayList<Long>();
        page.path("items").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private List<Long> order(List<Long> idsDescending) {
        return jdbc.queryForList("select id from user_event where recipient_id=? order by created_at desc, id desc",
                Long.class, student).stream().filter(idsDescending::contains).toList();
    }

    private void problem(JsonNode result, String field) {
        assertThat(result.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(result.path("fieldErrors")).anySatisfy(error -> assertThat(error.path("field").asText()).isEqualTo(field));
    }

    private String encode(String cursor) {
        return URLEncoder.encode(cursor, StandardCharsets.UTF_8);
    }
}

package ru.campusrating.feed;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.campusrating.feed.internal.IncompletePublicationsJob;
import ru.campusrating.poll.PollClosed;
import ru.campusrating.poll.PollCreated;
import ru.campusrating.poll.internal.PollClosingJob;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class PersonalFeedPollIT extends PersonalFeedSupport {
    static final Instant ENDS = Instant.parse("2026-11-20T15:00:00Z");
    static final String POLLS = "/api/v1/polls";

    @Autowired PollClosingJob closing;
    @Autowired IncompletePublicationsJob republication;

    @AfterEach
    void cleanPolls() {
        drainEvents();
        var tables = jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = current_schema() and (table_name = 'poll' or table_name like 'poll\\_%')
                """, String.class);
        if (!tables.isEmpty()) {
            update("truncate table " + String.join(", ", tables) + " cascade");
        }
        update("delete from event_publication where event_type like 'ru.campusrating.poll.%'");
    }

    @Test
    void ae1NewPollReachesOnlyActiveStudentsOfTargetGroups() {
        long pollId = poll("Формат зачёта", ENDS, group).path("id").asLong();
        var item = awaitFeed(studentSession, 1).getFirst();
        assertThat(item.path("kind").asText()).isEqualTo("POLL_CREATED");
        assertThat(item.path("pollId").asLong()).isEqualTo(pollId);
        assertThat(item.path("pollTitle").asText()).isEqualTo("Формат зачёта");
        assertThat(Instant.parse(item.path("endsAt").asText())).isEqualTo(ENDS);
        assertThat(Instant.parse(item.path("createdAt").asText())).isEqualTo(NOW);
        assertThat(pollEvents(classmate, "POLL_CREATED")).isEqualTo(1);
        assertThat(events(pending)).isZero();
        assertThat(events(neighbour)).isZero();
        assertThat(events(teacher)).isZero();
    }

    @Test
    void pollForTwoGroupsNotifiesEachActiveStudentOnce() {
        poll("Конференция", ENDS, group, otherGroup);
        drainEvents();
        for (long id : new long[]{student, classmate, neighbour}) {
            assertThat(pollEvents(id, "POLL_CREATED")).isEqualTo(1);
        }
        assertThat(events(pending)).isZero();
        assertThat(events(teacher)).isZero();
    }

    @Test
    void ae5ClosingNotifiesAuthorOnceWithVotedAndEligible() {
        var created = poll("Формат зачёта", ENDS, group);
        long pollId = created.path("id").asLong();
        long later = poll("Позже", ENDS.plus(Duration.ofDays(1)), group).path("id").asLong();
        long question = created.path("questions").get(0).path("id").asLong();
        long evening = created.path("questions").get(0).path("options").get(1).path("id").asLong();
        vote(pollId, studentSession, question, evening, 200);
        drainEvents();

        clock.set(ENDS.minusSeconds(1));
        closing.runOnce();
        drainEvents();
        assertThat(events(teacher)).isZero();

        clock.set(ENDS);
        closing.runOnce();
        var item = awaitFeed(teacherSession, 1).getFirst();
        assertThat(item.path("kind").asText()).isEqualTo("POLL_CLOSED");
        assertThat(item.path("pollId").asLong()).isEqualTo(pollId);
        assertThat(item.path("pollTitle").asText()).isEqualTo("Формат зачёта");
        assertThat(item.path("voted").asInt()).isEqualTo(1);
        assertThat(item.path("eligible").asInt()).isEqualTo(2);
        assertThat(Instant.parse(item.path("createdAt").asText())).isEqualTo(ENDS);

        clock.advance(Duration.ofMinutes(1));
        closing.runOnce();
        closing.runOnce();
        drainEvents();
        assertThat(pollEvents(teacher, "POLL_CLOSED")).isEqualTo(1);
        assertThat(count("select count(*) from event_publication where event_type=?", PollClosed.class.getName())).isZero();
        assertThat(pollEvents(student, "POLL_CLOSED")).isZero();
        assertThat(pollEvents(classmate, "POLL_CLOSED")).isZero();

        var vote = exchange(post(POLLS + "/" + pollId + "/vote").content(ballot(question, evening)), login("classmate"));
        assertThat(vote.status()).isEqualTo(422);
        assertThat(JSON.readTree(vote.body()).path("code").asText()).isEqualTo("POLL_CLOSED");

        var untouched = request(get(POLLS + "/" + later), teacherSession, 200);
        assertThat(untouched.path("open").asBoolean()).isTrue();
        assertThat(feed(teacherSession)).extracting(event -> event.path("pollId").asLong()).containsExactly(pollId);
    }

    @Test
    void failedCreationListenerIsRetriedWithoutDuplicates() {
        update("alter table user_event add constraint user_event_test_failure check (kind <> 'POLL_CREATED') not valid");
        try {
            poll("Формат зачёта", ENDS, group);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(count(
                    "select count(*) from event_publication where event_type=? and status='FAILED'",
                    PollCreated.class.getName())).isEqualTo(1));
            assertThat(events(student)).isZero();
        } finally {
            update("alter table user_event drop constraint user_event_test_failure");
        }
        republication.runOnce();
        assertThat(events(student)).isZero();
        clock.advance(Duration.ofMinutes(2));
        republication.runOnce();
        await().atMost(Duration.ofSeconds(10)).until(() -> events(student) == 1 && events(classmate) == 1);
        republication.runOnce();
        drainEvents();
        assertThat(pollEvents(student, "POLL_CREATED")).isEqualTo(1);
        assertThat(pollEvents(classmate, "POLL_CREATED")).isEqualTo(1);
        assertThat(count("select count(*) from event_publication where event_type=?", PollCreated.class.getName())).isZero();
    }

    @Test
    void failedClosingListenerIsRetriedAndJobDoesNotRepublish() {
        poll("Формат зачёта", ENDS, group);
        drainEvents();
        clock.set(ENDS);
        update("alter table user_event add constraint user_event_test_failure check (kind <> 'POLL_CLOSED') not valid");
        try {
            closing.runOnce();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(count(
                    "select count(*) from event_publication where event_type=? and status='FAILED'",
                    PollClosed.class.getName())).isEqualTo(1));
        } finally {
            update("alter table user_event drop constraint user_event_test_failure");
        }
        closing.runOnce();
        drainEvents();
        assertThat(events(teacher)).isZero();
        assertThat(count("select count(*) from event_publication where event_type=?", PollClosed.class.getName())).isEqualTo(1);
        clock.advance(Duration.ofMinutes(2));
        republication.runOnce();
        await().atMost(Duration.ofSeconds(10)).until(() -> events(teacher) == 1);
        republication.runOnce();
        closing.runOnce();
        drainEvents();
        assertThat(pollEvents(teacher, "POLL_CLOSED")).isEqualTo(1);
    }

    @Test
    void rejectedCreationPublishesNothing() {
        var invalid = pollBody("Формат зачёта", NOW.minusSeconds(1), group);
        assertThat(exchange(post(POLLS).content(JSON.writeValueAsString(invalid)), teacherSession).status()).isEqualTo(400);
        update("delete from teacher_approval where teacher_id=?", teacher);
        var forbidden = exchange(post(POLLS).content(JSON.writeValueAsString(pollBody("Формат зачёта", ENDS, group))),
                teacherSession);
        assertThat(forbidden.status()).isEqualTo(403);
        drainEvents();
        assertThat(count("select count(*) from event_publication where event_type=?", PollCreated.class.getName())).isZero();
        assertThat(events(student)).isZero();
        assertThat(events(classmate)).isZero();
    }

    private JsonNode poll(String title, Instant endsAt, long... groups) {
        return request(post(POLLS).content(JSON.writeValueAsString(pollBody(title, endsAt, groups))), teacherSession, 201);
    }

    private static Map<String, Object> pollBody(String title, Instant endsAt, long... groups) {
        return Map.of("title", title, "description", "", "endsAt", endsAt.toString(),
                "groupIds", Arrays.stream(groups).boxed().toList(), "anonymous", true,
                "questions", List.of(Map.of("text", "Когда удобнее?", "type", "SINGLE", "options", List.of("Утро", "Вечер"))));
    }

    private static String ballot(long question, long option) {
        return JSON.writeValueAsString(Map.of("answers", List.of(Map.of("questionId", question, "optionIds", List.of(option)))));
    }

    private void vote(long pollId, Browser session, long question, long option, int status) {
        request(post(POLLS + "/" + pollId + "/vote").content(ballot(question, option)), session, status);
    }

    private int pollEvents(long recipient, String kind) {
        return count("select count(*) from user_event where recipient_id=? and kind=?", recipient, kind);
    }
}

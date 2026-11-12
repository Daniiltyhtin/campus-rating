package ru.campusrating.feed;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalFeedActivityIT extends PersonalFeedSupport {
    static final Instant START = NOW.plus(Duration.ofDays(2));

    @Test
    void newActivityReachesActiveStudentsAndConfirmationAwardsPoints() {
        long subbotnik = activity("Субботник", "SOCIAL", START, 5, group);
        var created = awaitFeed(studentSession, 1).getFirst();
        assertThat(created.path("kind").asText()).isEqualTo("ACTIVITY_CREATED");
        assertThat(created.path("activityId").asLong()).isEqualTo(subbotnik);
        assertThat(created.path("activityTitle").asText()).isEqualTo("Субботник");
        assertThat(events(classmate)).isEqualTo(1);
        assertThat(events(pending)).isZero();
        assertThat(events(neighbour)).isZero();
        assertThat(events(teacher)).isZero();
        request(put("/api/v1/activities/" + subbotnik + "/enrollment"), studentSession, 200);
        clock.set(START);
        assertThat(confirm(subbotnik, student, 100).status()).isEqualTo(200);
        var awarded = awaitFeed(studentSession, 2).getFirst();
        assertThat(awarded.path("kind").asText()).isEqualTo("POINTS_AWARDED");
        assertThat(awarded.path("amount").asInt()).isEqualTo(5);
        assertThat(awarded.path("currency").asText()).isEqualTo("BOA");
        assertThat(awarded.path("activityId").asLong()).isEqualTo(subbotnik);
        assertThat(awarded.path("activityTitle").asText()).isEqualTo("Субботник");
        assertThat(Instant.parse(awarded.path("createdAt").asText())).isEqualTo(START);
    }

    @Test
    void academicAwardUsesBua() {
        long olympiad = activity("Олимпиада", "ACADEMIC", START, 40, group);
        clock.set(START);
        assertThat(confirm(olympiad, student, 50).status()).isEqualTo(200);
        var awarded = ofKind(awaitFeed(studentSession, 2), "POINTS_AWARDED").getFirst();
        assertThat(awarded.path("amount").asInt()).isEqualTo(20);
        assertThat(awarded.path("currency").asText()).isEqualTo("BUA");
    }

    @Test
    void budgetRejectionPublishesNoAward() {
        long big = activity("Большая", "ACADEMIC", START, 1000, group);
        long tiny = activity("Малая", "ACADEMIC", START, 4, group);
        clock.set(START);
        assertThat(confirm(big, student, 100).status()).isEqualTo(200);
        var rejected = confirm(tiny, classmate, 25);
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(JSON.readTree(rejected.body()).path("code").asText()).isEqualTo("BUDGET_EXCEEDED");
        drainEvents();
        assertThat(count("select count(*) from user_event where recipient_id=? and kind='POINTS_AWARDED'", classmate)).isZero();
        assertThat(count("select count(*) from user_event where recipient_id=? and kind='POINTS_AWARDED'", student)).isEqualTo(1);
    }

    @Test
    void activityForTwoGroupsNotifiesEachStudentOnce() {
        activity("Конференция", "ACADEMIC", START, 10, group, otherGroup);
        drainEvents();
        for (long id : new long[]{student, classmate, neighbour}) {
            assertThat(count("select count(*) from user_event where recipient_id=? and kind='ACTIVITY_CREATED'", id)).isEqualTo(1);
        }
        assertThat(events(pending)).isZero();
    }
}

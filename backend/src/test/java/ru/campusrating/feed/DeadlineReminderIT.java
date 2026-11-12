package ru.campusrating.feed;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.campusrating.feed.internal.DeadlineReminderJob;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineReminderIT extends PersonalFeedSupport {
    @Autowired DeadlineReminderJob job;

    @Test
    void tenPassesGiveOneReminderAndSubmittedWorkGetsNone() {
        long essay = checkpoint("Эссе", NOW.plus(Duration.ofHours(23)), group);
        long assignment = assignment(essay, student);
        submit(assignment(essay, classmate), login("classmate"));
        for (int pass = 0; pass < 10; pass++) {
            job.runOnce();
        }
        var reminders = ofKind(awaitFeed(studentSession, 2), "DEADLINE_REMINDER");
        assertThat(reminders).hasSize(1);
        assertThat(reminders.getFirst().path("assignmentId").asLong()).isEqualTo(assignment);
        assertThat(reminders.getFirst().path("checkpointTitle").asText()).isEqualTo("Эссе");
        assertThat(Instant.parse(reminders.getFirst().path("createdAt").asText())).isEqualTo(NOW);
        assertThat(reminders(classmate)).isZero();
    }

    @Test
    void windowIsUpToTwentyFourHoursInclusive() {
        long exact = checkpoint("Ровно сутки", NOW.plus(Duration.ofHours(24)), group);
        long later = checkpoint("Через 25 часов", NOW.plus(Duration.ofHours(25)), group);
        assignment(exact, student);
        assignment(later, student);
        drainEvents();
        job.runOnce();
        assertThat(titles(student)).containsExactly("Ровно сутки");
        clock.advance(Duration.ofHours(1));
        job.runOnce();
        assertThat(titles(student)).containsExactlyInAnyOrder("Ровно сутки", "Через 25 часов");
    }

    @Test
    void onlyInProgressAssignmentsAreReminded() {
        var session = login("classmate");
        long submitted = checkpoint("Сдано", NOW.plus(Duration.ofHours(2)), group);
        long accepted = checkpoint("Принято", NOW.plus(Duration.ofHours(2)), group);
        long returned = checkpoint("Возвращено", NOW.plus(Duration.ofHours(2)), group);
        long overdue = checkpoint("Просрочено", NOW.plus(Duration.ofMinutes(1)), group);
        submit(assignment(submitted, classmate), session);
        submit(assignment(accepted, classmate), session);
        accept(accepted, List.of(assignment(accepted, classmate)));
        submit(assignment(returned, classmate), session);
        returnWork(assignment(returned, classmate));
        assignment(overdue, classmate);
        drainEvents();
        clock.advance(Duration.ofMinutes(2));
        job.runOnce();
        assertThat(titles(classmate)).containsExactly("Возвращено");
    }

    private int reminders(long recipient) {
        return count("select count(*) from user_event where recipient_id=? and kind='DEADLINE_REMINDER'", recipient);
    }

    private List<String> titles(long recipient) {
        return jdbc.queryForList("select title from user_event where recipient_id=? and kind='DEADLINE_REMINDER' order by id",
                String.class, recipient);
    }
}

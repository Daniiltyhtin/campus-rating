package ru.campusrating.feed;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.campusrating.checkpoint.AssignmentSubmitted;
import ru.campusrating.feed.internal.IncompletePublicationsJob;
import ru.campusrating.identity.MembershipActivated;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class PersonalFeedEventsIT extends PersonalFeedSupport {
    @Autowired IncompletePublicationsJob republication;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationEventPublisher publisher;

    @Test
    void newCheckpointReachesOnlyActiveStudentsOfItsGroups() {
        long essay = checkpoint("Эссе", ESSAY_DEADLINE, group);
        long assignment = assignment(essay, student);
        var item = awaitFeed(studentSession, 1).getFirst();
        assertThat(item.path("kind").asText()).isEqualTo("ASSIGNMENT_CREATED");
        assertThat(item.path("assignmentId").asLong()).isEqualTo(assignment);
        assertThat(item.path("checkpointTitle").asText()).isEqualTo("Эссе");
        assertThat(Instant.parse(item.path("deadline").asText())).isEqualTo(ESSAY_DEADLINE);
        assertThat(Instant.parse(item.path("createdAt").asText())).isEqualTo(NOW);
        assertThat(events(classmate)).isEqualTo(1);
        assertThat(events(neighbour)).isZero();
        assertThat(events(pending)).isZero();
        assertThat(events(teacher)).isZero();
    }

    @Test
    void lateJoinerGetsNewTaskForReceivedAssignmentOnly() {
        long essay = checkpoint("Эссе", ESSAY_DEADLINE, group);
        assignment(essay, student);
        drainEvents();
        long orlov = register("orlov", "Орлов Олег Олегович", group);
        assertThat(exchange(post("/api/v1/memberships/" + orlov + "/approval"), teacherSession).status()).isEqualTo(204);
        long assignment = assignment(essay, orlov);
        var item = awaitFeed(login("orlov"), 1).getFirst();
        assertThat(item.path("kind").asText()).isEqualTo("ASSIGNMENT_CREATED");
        assertThat(item.path("assignmentId").asLong()).isEqualTo(assignment);
        update("update group_membership set activated_at=? where student_id=?", NOW, orlov);
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                publisher.publishEvent(new MembershipActivated(orlov, group, NOW)));
        drainEvents();
        assertThat(events(orlov)).isEqualTo(1);
    }

    @Test
    void everySubmissionNotifiesCheckpointAuthor() {
        long essay = checkpoint("Эссе", ESSAY_DEADLINE, group);
        long assignment = assignment(essay, student);
        submit(assignment, studentSession);
        var first = awaitFeed(teacherSession, 1).getFirst();
        assertThat(first.path("kind").asText()).isEqualTo("WORK_SUBMITTED");
        assertThat(first.path("checkpointId").asLong()).isEqualTo(essay);
        assertThat(first.path("checkpointTitle").asText()).isEqualTo("Эссе");
        assertThat(first.path("studentFullName").asText()).isEqualTo(STUDENT_NAME);
        returnWork(assignment);
        clock.advance(Duration.ofMinutes(1));
        submit(assignment, studentSession);
        assertThat(ofKind(awaitFeed(teacherSession, 2), "WORK_SUBMITTED")).hasSize(2);
    }

    @Test
    void returnThenAcceptNotifyStudentInOrder() {
        long essay = checkpoint("Эссе", ESSAY_DEADLINE, group);
        long assignment = assignment(essay, student);
        submit(assignment, studentSession);
        clock.advance(Duration.ofMinutes(1));
        returnWork(assignment);
        clock.advance(Duration.ofMinutes(1));
        submit(assignment, studentSession);
        clock.advance(Duration.ofMinutes(1));
        accept(essay, List.of(assignment));
        var items = awaitFeed(studentSession, 3);
        assertThat(items).extracting(item -> item.path("kind").asText())
                .containsExactly("WORK_ACCEPTED", "WORK_RETURNED", "ASSIGNMENT_CREATED");
        for (var item : items.subList(0, 2)) {
            assertThat(item.path("assignmentId").asLong()).isEqualTo(assignment);
            assertThat(item.path("checkpointTitle").asText()).isEqualTo("Эссе");
        }
    }

    @Test
    void batchAcceptNotifiesEveryAcceptedStudent() {
        long third = student("third", "Сидоров Сидор Сидорович", group, "ACTIVE");
        long essay = checkpoint("Эссе", ESSAY_DEADLINE, group);
        var students = Map.of(student, studentSession, classmate, login("classmate"), third, login("third"));
        var assignments = students.keySet().stream().map(id -> assignment(essay, id)).toList();
        students.forEach((id, session) -> submit(assignment(essay, id), session));
        assertThat(accept(essay, assignments).path("accepted")).hasSize(3);
        drainEvents();
        for (long id : students.keySet()) {
            assertThat(count("select count(*) from user_event where recipient_id=? and kind='WORK_ACCEPTED'", id)).isEqualTo(1);
        }
    }

    @Test
    void renamingCheckpointKeepsOldEventText() {
        long essay = checkpoint("Эссе", ESSAY_DEADLINE, group);
        assignment(essay, student);
        awaitFeed(studentSession, 1);
        request(patch("/api/v1/checkpoints/" + essay).content("{\"title\":\"Эссе 2\"}"), teacherSession, 200);
        assertThat(feed(studentSession).getFirst().path("checkpointTitle").asText()).isEqualTo("Эссе");
    }

    @Test
    void failedListenerIsRetriedByJobWithoutDuplicates() {
        long essay = checkpoint("Эссе", ESSAY_DEADLINE, group);
        long assignment = assignment(essay, student);
        drainEvents();
        update("alter table user_event add constraint user_event_test_failure check (kind <> 'WORK_SUBMITTED') not valid");
        try {
            submit(assignment, studentSession);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(count(
                    "select count(*) from event_publication where event_type=? and status='FAILED'",
                    AssignmentSubmitted.class.getName())).isEqualTo(1));
            assertThat(events(teacher)).isZero();
        } finally {
            update("alter table user_event drop constraint user_event_test_failure");
        }
        republication.runOnce();
        assertThat(events(teacher)).isZero();
        clock.advance(Duration.ofMinutes(2));
        republication.runOnce();
        await().atMost(Duration.ofSeconds(10)).until(() -> events(teacher) == 1);
        republication.runOnce();
        drainEvents();
        assertThat(events(teacher)).isEqualTo(1);
        assertThat(count("select count(*) from event_publication where event_type=?", AssignmentSubmitted.class.getName())).isZero();
        JsonNode item = feed(teacherSession).getFirst();
        assertThat(item.path("kind").asText()).isEqualTo("WORK_SUBMITTED");
    }
}

package ru.campusrating.checkpoint;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SubmissionWithdrawalIT extends SubmissionWithdrawalSupport {
    @Test
    void newCheckpointUsesFourPointStepAndSixtySecondInterval() {
        var point = create();
        assertThat(point.path("penaltyStep").asInt()).isEqualTo(4);
        assertThat(point.path("penaltyIntervalSeconds").asInt()).isEqualTo(60);
        var stored = author.get("/api/v1/checkpoints/" + point.path("id").asLong()).expect(200);
        assertThat(stored.path("penaltyStep").asInt()).isEqualTo(4);
        assertThat(stored.path("penaltyIntervalSeconds").asInt()).isEqualTo(60);
    }

    @Test
    void newCheckpointChargesFourPerIntervalAndRetriesDoNotChargeAgain() {
        long point = create().path("id").asLong();
        var rows = author.get("/api/v1/checkpoints/" + point + "/assignments").expect(200).path("items");
        long task = rows.valueStream().filter(n -> n.at("/student/id").asLong() == student)
                .findFirst().orElseThrow().path("assignmentId").asLong();
        clock.set(deadline.plusSeconds(30));
        job.runOnce();
        assertThat(owner.get("/api/v1/assignments/" + task).expect(200).at("/penalty/charged").asInt()).isEqualTo(4);
        job.runOnce();
        assertThat(owner.get("/api/v1/assignments/" + task).expect(200).at("/penalty/charged").asInt()).isEqualTo(4);
        for (int slot = 1; slot <= 15; slot++) {
            clock.set(deadline.plusSeconds(slot * 60L + 30));
            job.runOnce();
            assertThat(owner.get("/api/v1/assignments/" + task).expect(200).at("/penalty/charged").asInt())
                    .isEqualTo(Math.min((slot + 1) * 4, 60));
        }
        job.runOnce();
        assertThat(owner.get("/api/v1/assignments/" + task).expect(200).at("/penalty/phase").asText()).isEqualTo("CAP_REACHED");
    }

    @Test
    void legacyCheckpointKeepsTwentyPointStepAndSixtySecondInterval() {
        var point = author.get("/api/v1/checkpoints/" + checkpoint).expect(200);
        assertThat(point.path("penaltyStep").asInt()).isEqualTo(20);
        assertThat(point.path("penaltyIntervalSeconds").asInt()).isEqualTo(60);
        clock.set(deadline.plusSeconds(30));
        job.runOnce();
        assertThat(read().at("/penalty/charged").asInt()).isEqualTo(20);
    }

    @Test
    void ownerCanWithdrawBeforeDeadlineWithoutPenalty() {
        submit();
        clock.set(deadline.minusSeconds(1));
        var withdrawn = withdraw().expect(200);
        assertThat(withdrawn.path("state").asText()).isEqualTo("OPEN");
        assertThat(withdrawn.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(withdrawn.at("/penalty/charged").asInt()).isZero();
        job.runOnce();
        assertThat(read().path("state").asText()).isEqualTo("OPEN");
        assertThat(read().at("/penalty/charged").asInt()).isZero();
    }

    @Test
    void ownerCanSubmitNewVersionAfterWithdrawal() {
        long first = submit().at("/submission/id").asLong();
        withdraw().expect(200);
        clock.advance(Duration.ofSeconds(1));
        var resubmitted = submit();
        assertThat(resubmitted.path("state").asText()).isEqualTo("SUBMITTED");
        assertThat(resubmitted.path("submissions").size()).isEqualTo(2);
        assertThat(resubmitted.at("/submission/id").asLong()).isNotEqualTo(first);
        assertThat(resubmitted.path("submissions").valueStream().map(n -> n.path("id").asLong()).toList()).contains(first);
    }

    @Test
    void anotherStudentCannotWithdrawOwnersSubmission() {
        submit();
        problem(stranger.post(path() + "/submission/withdrawal", null), 403, "FORBIDDEN", null);
        assertThat(read().path("state").asText()).isEqualTo("SUBMITTED");
    }

    @Test
    void checkpointAuthorCannotWithdrawStudentsSubmission() {
        submit();
        problem(author.post(path() + "/submission/withdrawal", null), 403, "FORBIDDEN", null);
        assertThat(read().path("state").asText()).isEqualTo("SUBMITTED");
    }

    @Test
    void administratorCannotWithdrawStudentsSubmission() {
        submit();
        problem(administrator.post(path() + "/submission/withdrawal", null), 403, "FORBIDDEN", null);
        assertThat(read().path("state").asText()).isEqualTo("SUBMITTED");
    }

    @Test
    void withdrawalExactlyAtDeadlineIsRejected() {
        submit();
        clock.set(deadline);
        assertDeadlineConflict(withdraw());
    }

    @Test
    void withdrawalAfterDeadlineIsRejected() {
        submit();
        clock.set(deadline.plusSeconds(1));
        assertDeadlineConflict(withdraw());
    }

    @Test
    void withdrawalChecksDeadlineAfterAcquiringAssignmentLock() throws Exception {
        submit();
        clock.set(deadline.minusSeconds(1));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Future<Reply> pending;
            try (var connection = jdbc.getDataSource().getConnection()) {
                connection.setAutoCommit(false);
                int blocker;
                try (var statement = connection.createStatement();
                        var result = statement.executeQuery("select pg_backend_pid()")) {
                    result.next();
                    blocker = result.getInt(1);
                }
                try (var statement = connection.prepareStatement("select id from assignment where id=? for update")) {
                    statement.setLong(1, assignment);
                    statement.executeQuery().close();
                }
                pending = executor.submit(this::withdraw);
                try {
                    await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject(
                            "select exists(select 1 from pg_stat_activity where ? = any(pg_blocking_pids(pid)))",
                            Boolean.class, blocker));
                    clock.set(deadline.plusSeconds(5));
                } finally {
                    connection.rollback();
                }
            }
            assertDeadlineConflict(pending.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void acceptedSubmissionCannotBeWithdrawn() {
        submit();
        assertThat(accept().expect(200).path("accepted").valueStream().map(n -> n.asLong()).toList()).containsExactly(assignment);
        problem(withdraw(), 409, "INVALID_STATE", "Работу уже приняли — отозвать нельзя");
        assertThat(read().path("state").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    void openAssignmentCannotBeWithdrawn() {
        problem(withdraw(), 409, "INVALID_STATE", "Отозвать можно только сданную работу");
        assertThat(read().path("state").asText()).isEqualTo("OPEN");
        assertThat(read().path("submissions").size()).isZero();
    }

    @Test
    void repeatedWithdrawalDoesNotChangeHistoryOrState() {
        submit();
        Instant withdrawnAt = clock.instant();
        withdraw().expect(200);
        clock.advance(Duration.ofSeconds(1));
        problem(withdraw(), 409, "INVALID_STATE", "Отозвать можно только сданную работу");
        var result = read();
        assertThat(result.path("state").asText()).isEqualTo("OPEN");
        assertThat(result.path("submissions").size()).isEqualTo(1);
        assertThat(Instant.parse(result.at("/submissions/0/withdrawnAt").asText())).isEqualTo(withdrawnAt);
    }

    @Test
    void historyMarksOnlyWithdrawnVersionAndCheckpointRowHasNoCurrentSubmission() {
        long first = submit().at("/submission/id").asLong();
        clock.advance(Duration.ofSeconds(30));
        Instant withdrawnAt = clock.instant();
        withdraw().expect(200);
        for (var browser : List.of(owner, author, administrator)) {
            var history = browser.get(path()).expect(200).path("submissions");
            assertThat(history.size()).isEqualTo(1);
            assertThat(history.get(0).path("id").asLong()).isEqualTo(first);
            assertThat(Instant.parse(history.get(0).path("withdrawnAt").asText())).isEqualTo(withdrawnAt);
            assertThat(history.get(0).path("url").asText()).startsWith("https://example.org/");
        }
        var row = row();
        assertThat(row.path("state").asText()).isEqualTo("OPEN");
        assertThat(row.path("submittedAt").isNull()).isTrue();
        assertThat(row.path("submissionKind").isNull()).isTrue();
        clock.advance(Duration.ofSeconds(1));
        long second = submit().at("/submission/id").asLong();
        var versions = read().path("submissions");
        assertThat(versions.size()).isEqualTo(2);
        var old = versions.valueStream().filter(n -> n.path("id").asLong() == first).findFirst().orElseThrow();
        var current = versions.valueStream().filter(n -> n.path("id").asLong() == second).findFirst().orElseThrow();
        assertThat(Instant.parse(old.path("withdrawnAt").asText())).isEqualTo(withdrawnAt);
        assertThat(current.path("withdrawnAt").isNull()).isTrue();
        assertThat(row().path("submissionKind").asText()).isEqualTo("LINK");
        assertThat(Instant.parse(row().path("submittedAt").asText())).isEqualTo(clock.instant());
    }

    @Test
    void unwithdrawnVersionHasExplicitNullWithdrawalTime() {
        submit();
        assertThat(read().at("/submissions/0/withdrawnAt").isNull()).isTrue();
    }

    @Test
    void withdrawnFileRemainsDownloadableInHistory() {
        byte[] bytes = "%PDF-1.7\nWithdrawal acceptance fixture".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var submission = submitFile(bytes);
        long version = submission.at("/submission/id").asLong();
        assertThat(submission.at("/submissions/0/withdrawnAt").isNull()).isTrue();
        clock.advance(Duration.ofSeconds(1));
        Instant withdrawnAt = clock.instant();
        withdraw().expect(200);
        for (var browser : List.of(owner, author, administrator)) {
            var historical = browser.get(path()).expect(200).at("/submissions/0");
            assertThat(historical.path("kind").asText()).isEqualTo("FILE");
            assertThat(Instant.parse(historical.path("withdrawnAt").asText())).isEqualTo(withdrawnAt);
            assertThat(browser.download(path() + "/submissions/" + version + "/file")).isEqualTo(bytes);
        }
    }

    @Test
    void teacherSkipsWithdrawnSubmissionOnAcceptance() {
        submit();
        withdraw().expect(200);
        assertSkipped(accept().expect(200));
        assertThat(read().path("state").asText()).isEqualTo("OPEN");
    }

    @Test
    void concurrentWithdrawalAndAcceptanceHaveExactlyOneWinner() throws Exception {
        submit();
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var withdrawal = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return withdraw(); });
            var acceptance = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return accept(); });
            var withdrawn = withdrawal.get(15, TimeUnit.SECONDS);
            var accepted = acceptance.get(15, TimeUnit.SECONDS).expect(200);
            var finalState = read();
            assertThat(finalState.path("submissions").size()).isEqualTo(1);
            if (withdrawn.status() == 200) {
                assertThat(finalState.path("state").asText()).isEqualTo("OPEN");
                assertThat(finalState.path("acceptedAt").isNull()).isTrue();
                assertThat(Instant.parse(finalState.at("/submissions/0/withdrawnAt").asText())).isEqualTo(clock.instant());
                assertSkipped(accepted);
            } else {
                problem(withdrawn, 409, "INVALID_STATE", "Работу уже приняли — отозвать нельзя");
                assertThat(finalState.path("state").asText()).isEqualTo("ACCEPTED");
                assertThat(Instant.parse(finalState.path("acceptedAt").asText())).isEqualTo(clock.instant());
                assertThat(finalState.at("/submissions/0/withdrawnAt").isNull()).isTrue();
                assertThat(accepted.path("accepted").valueStream().map(n -> n.asLong()).toList()).containsExactly(assignment);
                assertThat(accepted.path("skipped").size()).isZero();
            }
        }
    }

    @Test
    void withdrawalWithoutCsrfIsRejectedWithoutChangingSubmission() {
        submit();
        problem(owner.withoutCsrf(path() + "/submission/withdrawal"), 403, "CSRF_INVALID", null);
        assertThat(read().path("state").asText()).isEqualTo("SUBMITTED");
    }

    private void assertDeadlineConflict(Reply reply) {
        problem(reply, 409, "INVALID_STATE", "Срок сдачи прошёл — отозвать работу нельзя");
        assertThat(read().path("state").asText()).isEqualTo("SUBMITTED");
    }

    private void assertSkipped(tools.jackson.databind.JsonNode result) {
        assertThat(result.path("accepted").size()).isZero();
        assertThat(result.path("skipped").size()).isEqualTo(1);
        assertThat(result.at("/skipped/0/assignmentId").asLong()).isEqualTo(assignment);
        assertThat(result.at("/skipped/0/code").asText()).isEqualTo("INVALID_STATE");
    }
}

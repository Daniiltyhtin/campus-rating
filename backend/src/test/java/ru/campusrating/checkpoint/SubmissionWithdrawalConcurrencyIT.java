package ru.campusrating.checkpoint;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class SubmissionWithdrawalConcurrencyIT extends SubmissionWithdrawalSupport {
    @Test
    void withdrawalSeesAcceptanceCommittedWhileWaitingForAssignment() throws Exception {
        submit();
        var reply = withdrawAfterLockedChange(connection -> {
            try (var statement = connection.prepareStatement(
                    "update assignment set state='ACCEPTED', accepted_at=? where id=?")) {
                statement.setTimestamp(1, Timestamp.from(clock.instant()));
                statement.setLong(2, assignment);
                statement.executeUpdate();
            }
        });
        problem(reply, 409, "INVALID_STATE", "Работу уже приняли — отозвать нельзя");
        var result = read();
        assertThat(result.path("state").asText()).isEqualTo("ACCEPTED");
        assertThat(result.at("/submissions/0/withdrawnAt").isNull()).isTrue();
    }

    @Test
    void withdrawalWithdrawsCurrentVersionCommittedWhileWaitingForAssignment() throws Exception {
        long previous = submit().at("/submission/id").asLong();
        withdraw().expect(200);
        clock.advance(Duration.ofSeconds(1));
        var reply = withdrawAfterLockedChange(connection -> {
            try (var statement = connection.prepareStatement("""
                    insert into submission(assignment_id,kind,url,submitted_at)
                    values (?,'LINK','https://example.org/new-version',?)
                    """)) {
                statement.setLong(1, assignment);
                statement.setTimestamp(2, Timestamp.from(clock.instant()));
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "update assignment set state='SUBMITTED' where id=?")) {
                statement.setLong(1, assignment);
                statement.executeUpdate();
            }
        });
        var result = reply.expect(200);
        assertThat(result.path("state").asText()).isEqualTo("OPEN");
        assertThat(result.path("submissions").size()).isEqualTo(2);
        var current = result.at("/submissions/0");
        assertThat(current.path("id").asLong()).isNotEqualTo(previous);
        assertThat(current.path("url").asText()).isEqualTo("https://example.org/new-version");
        assertThat(current.path("withdrawnAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(result.at("/submissions/1/withdrawnAt").asText())
                .isEqualTo(clock.instant().minusSeconds(1).toString());
        assertThat(read().path("state").asText()).isEqualTo("OPEN");
    }

    private Reply withdrawAfterLockedChange(LockedChange change) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Future<Reply> pending;
            try (var connection = jdbc.getDataSource().getConnection()) {
                connection.setAutoCommit(false);
                try {
                    int blocker;
                    try (var statement = connection.createStatement();
                            var result = statement.executeQuery("select pg_backend_pid()")) {
                        result.next();
                        blocker = result.getInt(1);
                    }
                    try (var statement = connection.prepareStatement(
                            "select id from assignment where id=? for update")) {
                        statement.setLong(1, assignment);
                        statement.executeQuery().close();
                    }
                    pending = executor.submit(this::withdraw);
                    assertThatThrownBy(() -> pending.get(500, TimeUnit.MILLISECONDS))
                            .isInstanceOf(TimeoutException.class);
                    await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject(
                            "select exists(select 1 from pg_stat_activity where ? = any(pg_blocking_pids(pid)))",
                            Boolean.class, blocker));
                    change.apply(connection);
                    connection.commit();
                } finally {
                    connection.rollback();
                }
            }
            return pending.get(10, TimeUnit.SECONDS);
        }
    }

    @FunctionalInterface
    private interface LockedChange {
        void apply(Connection connection) throws SQLException;
    }
}

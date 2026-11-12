package ru.campusrating.checkpoint.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.campusrating.checkpoint.AssignmentStatus;
import ru.campusrating.checkpoint.api.CheckpointAssignmentPage.StatusCounts;
import ru.campusrating.checkpoint.domain.AssignmentCursor;

@Repository
class AssignmentReadRepository {
    private static final String STATUS =
            """
            case when a.state='OPEN' then
                case when :now <= c.deadline then 'IN_PROGRESS' else 'OVERDUE' end
                else a.state end
            """;
    private static final String FROM =
            " from assignment a join checkpoint c on c.id=a.checkpoint_id ";
    private static final String SELECT =
            """
            select a.*, c.author_id, c.title, c.description, c.deadline, c.cost,
                c.penalty_step, c.penalty_interval_seconds, c.created_at as checkpoint_created_at,
                s.kind, s.submitted_at,
                exists(select 1 from submission where assignment_id=a.id) as has_submissions
            """
                    + FROM
                    + """
            left join lateral (
                select kind, submitted_at from submission where assignment_id=a.id and withdrawn_at is null
                order by submitted_at desc, id desc limit 1
            ) s on true
            """;
    private static final String GROUP = " and (:groupId is null or a.group_id=:groupId) ";
    private final JdbcClient jdbc;

    AssignmentReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<AssignmentReadData> active(long studentId, Instant now) {
        return jdbc.sql(
                        SELECT
                                + """
                where a.student_id=:studentId and a.state in ('OPEN','SUBMITTED')
                order by case when a.state='OPEN' and c.deadline < :now then 0 else 1 end,
                    c.deadline, a.id
                """)
                .param("studentId", studentId)
                .param("now", Timestamp.from(now))
                .query(this::row)
                .list();
    }

    List<AssignmentReadData> completed(long studentId, AssignmentCursor cursor, int limit) {
        return jdbc.sql(
                        SELECT
                                + """
                where a.student_id=:studentId and a.state='ACCEPTED'
                    and (:afterId is null or (a.accepted_at,a.id) < (:acceptedAt,:afterId))
                order by a.accepted_at desc, a.id desc limit :limit
                """)
                .param("studentId", studentId)
                .param("afterId", cursor == null ? null : cursor.id(), Types.BIGINT)
                .param(
                        "acceptedAt",
                        cursor == null ? null : Timestamp.from(cursor.acceptedAt()),
                        Types.TIMESTAMP)
                .param("limit", limit)
                .query(this::row)
                .list();
    }

    List<AssignmentReadData> table(
            long checkpoint,
            AssignmentStatus status,
            Long groupId,
            AssignmentCursor cursor,
            int limit,
            Instant now) {
        return jdbc.sql(
                        SELECT
                                + " where a.checkpoint_id=:checkpointId "
                                + GROUP
                                + " and (:status is null or ("
                                + STATUS
                                + ")=:status) "
                                + """
                        and (:afterId is null or (a.student_full_name,a.id) > (:fullName,:afterId))
                        order by a.student_full_name, a.id limit :limit
                        """)
                .param("checkpointId", checkpoint)
                .param("groupId", groupId, Types.BIGINT)
                .param("status", status == null ? null : status.name(), Types.VARCHAR)
                .param("now", Timestamp.from(now))
                .param("afterId", cursor == null ? null : cursor.id(), Types.BIGINT)
                .param("fullName", cursor == null ? null : cursor.key(), Types.VARCHAR)
                .param("limit", limit)
                .query(this::row)
                .list();
    }

    StatusCounts counts(long checkpoint, Long groupId, Instant now) {
        return jdbc.sql(
                        """
                select count(*) filter (where status='IN_PROGRESS') as in_progress,
                    count(*) filter (where status='SUBMITTED') as submitted,
                    count(*) filter (where status='OVERDUE') as overdue,
                    count(*) filter (where status='ACCEPTED') as accepted
                from (select
                """
                                + STATUS
                                + " as status "
                                + FROM
                                + " where a.checkpoint_id=:checkpointId "
                                + GROUP
                                + ") statuses")
                .param("checkpointId", checkpoint)
                .param("groupId", groupId, Types.BIGINT)
                .param("now", Timestamp.from(now))
                .query(
                        (rs, index) ->
                                new StatusCounts(
                                        rs.getInt("in_progress"),
                                        rs.getInt("submitted"),
                                        rs.getInt("overdue"),
                                        rs.getInt("accepted")))
                .single();
    }

    private AssignmentReadData row(ResultSet rs, int index) throws SQLException {
        var assignment = SubmissionRepository.assignment(rs, index);
        var checkpoint =
                new CheckpointData(
                        assignment.checkpointId(),
                        rs.getLong("author_id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getTimestamp("deadline").toInstant(),
                        rs.getInt("cost"),
                        rs.getInt("penalty_step"),
                        rs.getInt("penalty_interval_seconds"),
                        rs.getTimestamp("checkpoint_created_at").toInstant(),
                        0,
                        0);
        Timestamp submittedAt = rs.getTimestamp("submitted_at");
        return new AssignmentReadData(
                assignment,
                checkpoint,
                submittedAt == null ? null : submittedAt.toInstant(),
                rs.getString("kind"),
                rs.getBoolean("has_submissions"));
    }
}

package ru.campusrating.checkpoint.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.campusrating.checkpoint.AssignmentState;
import ru.campusrating.shared.storage.FileStorage;

@Repository
class SubmissionRepository {
    private final JdbcClient jdbc;

    SubmissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<AssignmentData> find(long id, boolean lock) {
        return jdbc.sql("select * from assignment where id=?" + (lock ? " for update" : ""))
                .param(id)
                .query(SubmissionRepository::assignment)
                .optional();
    }

    List<AssignmentData> lock(List<Long> ids) {
        return jdbc.sql("select * from assignment where id in (:ids) order by id for update")
                .param("ids", ids)
                .query(SubmissionRepository::assignment)
                .list();
    }

    List<SubmissionData> versions(long assignment) {
        return jdbc.sql(
                        "select * from submission where assignment_id=? order by submitted_at"
                            + " desc,id desc")
                .param(assignment)
                .query(this::submission)
                .list();
    }

    Optional<SubmissionData> version(long assignment, long version) {
        return jdbc.sql("select * from submission where assignment_id=? and id=?")
                .params(assignment, version)
                .query(this::submission)
                .optional();
    }

    long link(long assignment, String url, Instant now) {
        return jdbc.sql(
                        "insert into submission(assignment_id,kind,url,submitted_at) values"
                            + " (?,'LINK',?,?) returning id")
                .params(assignment, url, Timestamp.from(now))
                .query(Long.class)
                .single();
    }

    long file(long assignment, String key, FileStorage.Metadata meta, Instant now) {
        return jdbc.sql(
                        """
                insert into submission(assignment_id,kind,file_key,file_name,size_bytes,content_type,submitted_at)
                values (?,'FILE',?,?,?,?,?) returning id
                """)
                .params(
                        assignment,
                        key,
                        meta.fileName(),
                        meta.sizeBytes(),
                        meta.contentType(),
                        Timestamp.from(now))
                .query(Long.class)
                .single();
    }

    void state(long id, AssignmentState state) {
        jdbc.sql("update assignment set state=? where id=?").params(state.name(), id).update();
    }

    void accept(long id, Instant now) {
        jdbc.sql("update assignment set state='ACCEPTED',accepted_at=? where id=?")
                .params(Timestamp.from(now), id)
                .update();
    }

    void withdrawVersion(long version, Instant now) {
        jdbc.sql("update submission set withdrawn_at=? where id=?")
                .params(Timestamp.from(now), version)
                .update();
    }

    void returnVersion(long version, String comment, Instant now) {
        jdbc.sql("update submission set return_comment=?,returned_at=? where id=?")
                .params(comment, Timestamp.from(now), version)
                .update();
    }

    static AssignmentData assignment(ResultSet rs, int row) throws SQLException {
        Number slot = (Number) rs.getObject("last_penalty_slot");
        return new AssignmentData(
                rs.getLong("id"),
                rs.getLong("checkpoint_id"),
                rs.getLong("student_id"),
                rs.getLong("group_id"),
                rs.getString("student_full_name"),
                AssignmentState.valueOf(rs.getString("state")),
                rs.getInt("charged"),
                slot == null ? null : slot.longValue(),
                instant(rs, "accepted_at"));
    }

    private SubmissionData submission(ResultSet rs, int row) throws SQLException {
        return new SubmissionData(
                rs.getLong("id"),
                rs.getString("kind"),
                rs.getString("url"),
                rs.getString("file_key"),
                rs.getString("file_name"),
                rs.getObject("size_bytes", Long.class),
                rs.getString("content_type"),
                instant(rs, "submitted_at"),
                rs.getString("return_comment"),
                instant(rs, "returned_at"),
                instant(rs, "withdrawn_at"));
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
}

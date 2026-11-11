package ru.campusrating.checkpoint.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.campusrating.checkpoint.api.CheckpointCreate;
import ru.campusrating.identity.StudentMember;

@Repository
class CheckpointRepository {
    private static final String SELECT = """
            select c.*,
                (select count(*) from assignment a where a.checkpoint_id=c.id and a.state='SUBMITTED') as submitted_count,
                (select count(*) from assignment a where a.checkpoint_id=c.id) as total_count
            from checkpoint c
            """;
    private final JdbcClient jdbc;
    CheckpointRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    long create(long author, CheckpointCreate request, int step, int interval, Instant now) {
        return jdbc.sql("""
                insert into checkpoint(author_id,title,description,deadline,cost,penalty_step,
                    penalty_interval_seconds,created_at,updated_at)
                values (:author,:title,:description,:deadline,:cost,:step,:interval,:now,:now) returning id
                """).param("author", author).param("title", request.title()).param("description", request.description())
                .param("deadline", Timestamp.from(request.deadline())).param("cost", request.cost())
                .param("step", step).param("interval", interval).param("now", Timestamp.from(now)).query(Long.class).single();
    }

    void addGroup(long checkpointId, long groupId) {
        jdbc.sql("insert into checkpoint_group(checkpoint_id,group_id) values (?,?)").params(checkpointId, groupId).update();
    }

    Optional<CheckpointData> find(long id) { return jdbc.sql(SELECT + " where c.id=?").param(id).query(this::row).optional(); }

    List<CheckpointData> byAuthor(long author, Instant now) {
        return jdbc.sql(SELECT + """
                 where c.author_id=:author
                 order by case when c.deadline > :now then 0 else 1 end,
                    case when c.deadline > :now then c.deadline end asc,
                    case when c.deadline <= :now then c.deadline end desc, c.id asc
                """).param("author", author).param("now", Timestamp.from(now)).query(this::row).list();
    }

    List<Long> groups(long checkpointId) {
        return jdbc.sql("select group_id from checkpoint_group where checkpoint_id=? order by group_id")
                .param(checkpointId).query(Long.class).list();
    }

    List<CheckpointData> forActivation(long groupId, Instant activatedAt) {
        return jdbc.sql(SELECT + """
                 join checkpoint_group g on g.checkpoint_id=c.id
                 where g.group_id=? and c.deadline > ? order by c.id
                """).params(groupId, Timestamp.from(activatedAt)).query(this::row).list();
    }

    void assign(CheckpointData checkpoint, StudentMember member, Instant activatedAt, Instant now) {
        jdbc.sql("""
                insert into assignment(checkpoint_id,student_id,group_id,student_full_name,state,created_at)
                select :checkpoint,:student,:group,:name,'OPEN',:now
                where :activated < :deadline
                on conflict (checkpoint_id,student_id) do nothing
                """).param("checkpoint", checkpoint.id()).param("student", member.studentId()).param("group", member.groupId())
                .param("name", member.fullName()).param("now", Timestamp.from(now)).param("activated", Timestamp.from(activatedAt))
                .param("deadline", Timestamp.from(checkpoint.deadline())).update();
    }

    void update(long id, String title, String description, Instant now) {
        jdbc.sql("update checkpoint set title=coalesce(:title,title), description=coalesce(:description,description), updated_at=:now where id=:id")
                .param("title", title, Types.VARCHAR).param("description", description, Types.VARCHAR)
                .param("now", Timestamp.from(now)).param("id", id).update();
    }

    private CheckpointData row(ResultSet rs, int row) throws SQLException {
        return new CheckpointData(rs.getLong("id"), rs.getLong("author_id"), rs.getString("title"), rs.getString("description"),
                rs.getTimestamp("deadline").toInstant(), rs.getInt("cost"), rs.getInt("penalty_step"), rs.getInt("penalty_interval_seconds"),
                rs.getTimestamp("created_at").toInstant(), rs.getInt("submitted_count"), rs.getInt("total_count"));
    }
}

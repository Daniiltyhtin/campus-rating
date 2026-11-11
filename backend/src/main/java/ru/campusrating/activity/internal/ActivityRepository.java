package ru.campusrating.activity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.campusrating.activity.domain.ActivityType;

@Repository
class ActivityRepository {
    record ActivityRow(long id, long authorId, String title, String description, ActivityType type,
                       Instant startsAt, int maxPoints, int enrolledCount, int confirmedCount,
                       boolean enrolled, Integer awardLevel, Integer awardAmount) {}

    private record Link(long activity, long group) {}

    private static final String PROJECTION = """
            select a.*,
                (select count(*) from activity_enrollment e where e.activity_id=a.id) enrolled_count,
                (select count(*) from activity_award w where w.activity_id=a.id) confirmed_count,
                exists(select 1 from activity_enrollment e where e.activity_id=a.id and e.student_id=:viewer) enrolled,
                w.level award_level, w.amount award_amount
            from activity a left join activity_award w on w.activity_id=a.id and w.student_id=:viewer
            """;

    private final JdbcClient jdbc;

    ActivityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long insert(long author, String title, String description, ActivityType type, Instant startsAt, int maxPoints, Instant now) {
        return jdbc.sql("""
                insert into activity(author_id,title,description,type,starts_at,max_points,created_at)
                values (:author,:title,:description,:type,:start,:max,:now) returning id
                """)
                .param("author", author)
                .param("title", title)
                .param("description", description)
                .param("type", type.name())
                .param("start", Timestamp.from(startsAt))
                .param("max", maxPoints)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
    }

    void addGroups(long activity, Collection<Long> groups) {
        for (long group : groups) {
            jdbc.sql("insert into activity_group(activity_id,group_id) values (:activity,:group)")
                    .param("activity", activity)
                    .param("group", group)
                    .update();
        }
    }

    List<ActivityRow> list(long viewer, boolean teacher, Long groupId, Instant now) {
        String filter = teacher
                ? "a.author_id=:viewer"
                : "exists(select 1 from activity_group g where g.activity_id=a.id and g.group_id=:group)";
        var statement = jdbc.sql(PROJECTION + " where " + filter + """
                 order by case when a.starts_at > :now then 0 else 1 end,
                 case when a.starts_at > :now then a.starts_at end asc,
                 case when a.starts_at <= :now then a.starts_at end desc, a.id
                """)
                .param("viewer", viewer)
                .param("now", Timestamp.from(now));
        if (!teacher) {
            statement.param("group", groupId);
        }
        return statement.query(this::row).list();
    }

    Optional<ActivityRow> find(long activity, long viewer) {
        return jdbc.sql(PROJECTION + " where a.id=:id")
                .param("id", activity)
                .param("viewer", viewer)
                .query(this::row)
                .optional();
    }

    void lock(long activity) {
        jdbc.sql("select id from activity where id=:id for update")
                .param("id", activity)
                .query(Long.class)
                .optional();
    }

    Map<Long, List<Long>> groups(Collection<Long> activityIds) {
        var result = new LinkedHashMap<Long, List<Long>>();
        if (activityIds.isEmpty()) {
            return result;
        }
        var links = jdbc.sql("select activity_id,group_id from activity_group where activity_id in (:ids) order by group_id")
                .param("ids", activityIds)
                .query((rs, index) -> new Link(rs.getLong("activity_id"), rs.getLong("group_id")))
                .list();
        for (var link : links) {
            result.computeIfAbsent(link.activity(), key -> new ArrayList<>()).add(link.group());
        }
        return result;
    }

    Set<Long> authorGroups(long author) {
        var groups = jdbc.sql("""
                select distinct g.group_id from activity_group g join activity a on a.id=g.activity_id
                where a.author_id=:author
                """)
                .param("author", author)
                .query(Long.class)
                .list();
        return new TreeSet<>(groups);
    }

    Set<Long> enrolledStudents(long activity) {
        var students = jdbc.sql("select student_id from activity_enrollment where activity_id=:activity")
                .param("activity", activity)
                .query(Long.class)
                .list();
        return new TreeSet<>(students);
    }

    void enroll(long activity, long student, Instant now) {
        jdbc.sql("""
                insert into activity_enrollment(activity_id,student_id,enrolled_at) values (:activity,:student,:now)
                on conflict do nothing
                """)
                .param("activity", activity)
                .param("student", student)
                .param("now", Timestamp.from(now))
                .update();
    }

    void cancel(long activity, long student) {
        jdbc.sql("delete from activity_enrollment where activity_id=:activity and student_id=:student")
                .param("activity", activity)
                .param("student", student)
                .update();
    }

    private ActivityRow row(ResultSet rs, int index) throws SQLException {
        return new ActivityRow(
                rs.getLong("id"),
                rs.getLong("author_id"),
                rs.getString("title"),
                rs.getString("description"),
                ActivityType.valueOf(rs.getString("type")),
                rs.getTimestamp("starts_at").toInstant(),
                rs.getInt("max_points"),
                rs.getInt("enrolled_count"),
                rs.getInt("confirmed_count"),
                rs.getBoolean("enrolled"),
                rs.getObject("award_level", Integer.class),
                rs.getObject("award_amount", Integer.class));
    }
}

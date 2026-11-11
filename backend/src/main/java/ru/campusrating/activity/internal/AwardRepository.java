package ru.campusrating.activity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.campusrating.activity.domain.ActivityType;
import ru.campusrating.ledger.Currency;

@Repository
class AwardRepository {
    record AwardRow(long id, long studentId, long groupId, int level, int amount, Instant confirmedAt) {}

    record HistoryRow(long id, long activityId, String activityTitle, ActivityType activityType, long studentId,
                      long groupId, int level, int amount, Currency currency, Instant confirmedAt) {}

    record SummaryRow(long groupId, Currency currency, int amount, int count) {}

    private final JdbcClient jdbc;

    AwardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<AwardRow> forActivity(long activity) {
        return jdbc.sql("""
                select id,student_id,group_id,level,amount,confirmed_at from activity_award
                where activity_id=:activity order by student_id
                """)
                .param("activity", activity)
                .query(this::award)
                .list();
    }

    long insert(long activity, long author, NewAward award, Currency currency, Instant now) {
        return jdbc.sql("""
                insert into activity_award(activity_id,student_id,group_id,level,amount,currency,author_id,confirmed_at)
                values (:activity,:student,:group,:level,:amount,:currency,:author,:now) returning id
                """)
                .param("activity", activity)
                .param("student", award.studentId())
                .param("group", award.groupId())
                .param("level", award.level())
                .param("amount", award.amount())
                .param("currency", currency.name())
                .param("author", author)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
    }

    void link(long award, long entry) {
        jdbc.sql("update activity_award set ledger_entry_id=:entry where id=:id")
                .param("entry", entry)
                .param("id", award)
                .update();
    }

    List<HistoryRow> history(long author, @Nullable Long groupId, MonthRange month, @Nullable AwardCursor cursor, int limit) {
        var sql = new StringBuilder("""
                select w.id,w.activity_id,a.title,a.type,w.student_id,w.group_id,w.level,w.amount,w.currency,w.confirmed_at
                from activity_award w join activity a on a.id=w.activity_id
                where w.author_id=:author and w.confirmed_at >= :from and w.confirmed_at < :to
                """);
        if (groupId != null) {
            sql.append(" and w.group_id=:group");
        }
        if (cursor != null) {
            sql.append(" and (w.confirmed_at < :at or (w.confirmed_at = :at and w.id < :id))");
        }
        sql.append(" order by w.confirmed_at desc, w.id desc limit :limit");
        var statement = jdbc.sql(sql.toString())
                .param("author", author)
                .param("from", Timestamp.from(month.from()))
                .param("to", Timestamp.from(month.to()))
                .param("limit", limit);
        if (groupId != null) {
            statement.param("group", groupId);
        }
        if (cursor != null) {
            statement.param("at", Timestamp.from(cursor.confirmedAt()));
            statement.param("id", cursor.id());
        }
        return statement.query(this::history).list();
    }

    List<SummaryRow> summary(long author, MonthRange month) {
        return jdbc.sql("""
                select group_id,currency,sum(amount) amount,count(*) count from activity_award
                where author_id=:author and confirmed_at >= :from and confirmed_at < :to
                group by group_id,currency
                """)
                .param("author", author)
                .param("from", Timestamp.from(month.from()))
                .param("to", Timestamp.from(month.to()))
                .query((rs, index) -> new SummaryRow(rs.getLong("group_id"), Currency.valueOf(rs.getString("currency")),
                        rs.getInt("amount"), rs.getInt("count")))
                .list();
    }

    private AwardRow award(ResultSet rs, int index) throws SQLException {
        return new AwardRow(
                rs.getLong("id"),
                rs.getLong("student_id"),
                rs.getLong("group_id"),
                rs.getInt("level"),
                rs.getInt("amount"),
                rs.getTimestamp("confirmed_at").toInstant());
    }

    private HistoryRow history(ResultSet rs, int index) throws SQLException {
        return new HistoryRow(
                rs.getLong("id"),
                rs.getLong("activity_id"),
                rs.getString("title"),
                ActivityType.valueOf(rs.getString("type")),
                rs.getLong("student_id"),
                rs.getLong("group_id"),
                rs.getInt("level"),
                rs.getInt("amount"),
                Currency.valueOf(rs.getString("currency")),
                rs.getTimestamp("confirmed_at").toInstant());
    }
}

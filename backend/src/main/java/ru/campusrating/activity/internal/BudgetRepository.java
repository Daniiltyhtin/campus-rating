package ru.campusrating.activity.internal;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class BudgetRepository {
    private record Spent(long groupId, int spent) {}

    private final JdbcClient jdbc;

    BudgetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void ensure(long teacher, Collection<Long> groups, LocalDate month) {
        for (long group : groups) {
            jdbc.sql("""
                    insert into activity_budget(teacher_id,group_id,month,spent) values (:teacher,:group,:month,0)
                    on conflict (teacher_id,group_id,month) do nothing
                    """)
                    .param("teacher", teacher)
                    .param("group", group)
                    .param("month", month)
                    .update();
        }
    }

    Map<Long, Integer> lock(long teacher, Collection<Long> groups, LocalDate month) {
        return spent(teacher, groups, month, " order by group_id for update");
    }

    Map<Long, Integer> spent(long teacher, Collection<Long> groups, LocalDate month) {
        return spent(teacher, groups, month, "");
    }

    void spend(long teacher, long group, LocalDate month, int amount) {
        jdbc.sql("update activity_budget set spent=spent+:amount where teacher_id=:teacher and group_id=:group and month=:month")
                .param("amount", amount)
                .param("teacher", teacher)
                .param("group", group)
                .param("month", month)
                .update();
    }

    private Map<Long, Integer> spent(long teacher, Collection<Long> groups, LocalDate month, String suffix) {
        var result = new HashMap<Long, Integer>();
        if (groups.isEmpty()) {
            return result;
        }
        var rows = jdbc.sql("""
                select group_id,spent from activity_budget
                where teacher_id=:teacher and month=:month and group_id in (:groups)
                """ + suffix)
                .param("teacher", teacher)
                .param("month", month)
                .param("groups", groups)
                .query((rs, index) -> new Spent(rs.getLong("group_id"), rs.getInt("spent")))
                .list();
        for (var row : rows) {
            result.put(row.groupId(), row.spent());
        }
        return result;
    }

    java.util.List<ru.campusrating.activity.BudgetStatistics.Month> summarize(long groupId, java.time.YearMonth from,
            java.time.YearMonth to, java.util.Optional<Long> teacherId) {
        String filter = teacherId.isPresent() ? " and author_id=:teacher" : "";
        var query = jdbc.sql("""
                with totals as (
                    select (confirmed_at at time zone 'Europe/Moscow')::date -
                               (extract(day from confirmed_at at time zone 'Europe/Moscow')::int - 1) as award_month,
                           author_id teacher_id, count(*) awards,
                           coalesce(sum(amount) filter (where currency='BUA'),0) bua,
                           coalesce(sum(amount) filter (where currency='BOA'),0) boa
                    from activity_award where group_id=:group
                    and confirmed_at >= :from and confirmed_at < :until
                """ + filter + """
                    group by award_month, author_id
                )
                select t.*, coalesce(b.spent,0) spent from totals t
                left join activity_budget b on b.teacher_id=t.teacher_id and b.group_id=:group and b.month=t.award_month
                order by t.award_month desc, t.teacher_id
                """)
                .param("group", groupId)
                .param("from", java.sql.Timestamp.from(from.atDay(1).atStartOfDay(java.time.ZoneId.of("Europe/Moscow")).toInstant()))
                .param("until", java.sql.Timestamp.from(to.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneId.of("Europe/Moscow")).toInstant()));
        teacherId.ifPresent(id -> query.param("teacher", id));
        return query.query((rs, row) -> new ru.campusrating.activity.BudgetStatistics.Month(
                java.time.YearMonth.from(rs.getDate("award_month").toLocalDate()), rs.getLong("teacher_id"),
                rs.getInt("spent"), rs.getInt("awards"), rs.getInt("bua"), rs.getInt("boa"))).list();
    }

}

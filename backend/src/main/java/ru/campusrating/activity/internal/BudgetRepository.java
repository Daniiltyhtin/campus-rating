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
}

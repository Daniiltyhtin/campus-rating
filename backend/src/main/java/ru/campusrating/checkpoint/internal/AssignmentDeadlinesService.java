package ru.campusrating.checkpoint.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.campusrating.checkpoint.AssignmentDeadlines;

@Service
class AssignmentDeadlinesService implements AssignmentDeadlines {
    private final JdbcClient jdbc;

    AssignmentDeadlinesService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DueAssignment> inProgressDueBy(Instant now, Instant until) {
        return jdbc.sql("""
                select a.id, a.student_id, c.title, c.deadline
                from checkpoint c join assignment a on a.checkpoint_id=c.id
                where a.state='OPEN' and c.deadline > :now and c.deadline <= :until
                order by a.id
                """).param("now", Timestamp.from(now)).param("until", Timestamp.from(until))
                .query((rs, row) -> new DueAssignment(rs.getLong("id"), rs.getLong("student_id"), rs.getString("title"),
                        rs.getTimestamp("deadline").toInstant()))
                .list();
    }
}

package ru.campusrating.checkpoint.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PenaltyRepository {
    private final JdbcClient jdbc;

    PenaltyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Long> candidates(Instant now) {
        return jdbc.sql(
                        """
                select a.id from assignment a join checkpoint c on c.id=a.checkpoint_id
                where a.state='OPEN' and c.deadline < :now and a.charged < c.cost
                  and (a.last_penalty_slot is null or a.last_penalty_slot <
                    floor(extract(epoch from (cast(:now as timestamptz) - c.deadline))
                        / c.penalty_interval_seconds))
                order by a.id limit 500
                """)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .list();
    }

    void processed(long id, int charged, long slot) {
        jdbc.sql("update assignment set charged=?,last_penalty_slot=? where id=?")
                .params(charged, slot, id)
                .update();
    }
}

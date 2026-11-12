package ru.campusrating.feed.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.campusrating.shared.domain.TimeIdCursor;
import ru.campusrating.feed.domain.UserEventKind;
import ru.campusrating.ledger.Currency;

@Repository
class UserEventRepository {
    record NewEvent(long recipientId, UserEventKind kind, String dedupKey, Instant createdAt, long targetId, String title,
                    @Nullable Instant deadline, @Nullable Integer amount, @Nullable Currency currency,
                    @Nullable String studentFullName) {}

    record EventRow(long id, UserEventKind kind, Instant createdAt, long targetId, String title, @Nullable Instant deadline,
                    @Nullable Integer amount, @Nullable Currency currency, @Nullable String studentFullName) {}

    private final JdbcClient jdbc;

    UserEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(NewEvent event) {
        jdbc.sql("""
                insert into user_event(recipient_id,kind,dedup_key,created_at,target_id,title,deadline,amount,currency,student_full_name)
                values (:recipient,:kind,:key,:createdAt,:target,:title,:deadline,:amount,:currency,:student)
                on conflict (recipient_id,dedup_key) do nothing
                """)
                .param("recipient", event.recipientId())
                .param("kind", event.kind().name())
                .param("key", event.dedupKey())
                .param("createdAt", Timestamp.from(event.createdAt().truncatedTo(ChronoUnit.MICROS)))
                .param("target", event.targetId())
                .param("title", event.title())
                .param("deadline", event.deadline() == null ? null : Timestamp.from(event.deadline()), Types.TIMESTAMP)
                .param("amount", event.amount(), Types.INTEGER)
                .param("currency", event.currency() == null ? null : event.currency().name(), Types.VARCHAR)
                .param("student", event.studentFullName(), Types.VARCHAR)
                .update();
    }

    List<EventRow> page(long recipientId, @Nullable TimeIdCursor cursor, int limit) {
        var sql = cursor == null
                ? "select * from user_event where recipient_id=:recipient order by created_at desc, id desc limit :limit"
                : """
                select * from user_event where recipient_id=:recipient and (created_at, id) < (:createdAt, :id)
                order by created_at desc, id desc limit :limit
                """;
        var query = jdbc.sql(sql).param("recipient", recipientId).param("limit", limit);
        if (cursor != null) {
            query = query.param("createdAt", Timestamp.from(cursor.createdAt())).param("id", cursor.id());
        }
        return query.query(UserEventRepository::row).list();
    }

    private static EventRow row(ResultSet rs, int row) throws SQLException {
        var deadline = rs.getTimestamp("deadline");
        var currency = rs.getString("currency");
        return new EventRow(rs.getLong("id"), UserEventKind.valueOf(rs.getString("kind")),
                rs.getTimestamp("created_at").toInstant(), rs.getLong("target_id"), rs.getString("title"),
                deadline == null ? null : deadline.toInstant(), rs.getObject("amount", Integer.class),
                currency == null ? null : Currency.valueOf(currency), rs.getString("student_full_name"));
    }
}

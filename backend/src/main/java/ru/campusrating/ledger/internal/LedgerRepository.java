package ru.campusrating.ledger.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.campusrating.ledger.Author;
import ru.campusrating.ledger.BalanceMismatch;
import ru.campusrating.ledger.Currency;
import ru.campusrating.ledger.EntryKind;
import ru.campusrating.ledger.LedgerEntryView;
import ru.campusrating.ledger.Posting;
import ru.campusrating.ledger.PostingResult;
import ru.campusrating.ledger.Source;
import ru.campusrating.ledger.SourceType;
import ru.campusrating.ledger.domain.LedgerCursor;

@Repository
class LedgerRepository {
    record BalanceKey(long studentId, Currency currency) {}
    record BalanceRow(BalanceKey key, int amount) {}
    record Entry(long studentId, LedgerEntryView view) {}

    private final JdbcClient jdbc;

    LedgerRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    void ensureBalances(List<BalanceKey> sortedKeys) {
        for (var key : sortedKeys) {
            jdbc.sql("""
                    insert into ledger_balance (student_id, currency, amount) values (:student, :currency, 0)
                    on conflict do nothing
                    """)
                    .param("student", key.studentId()).param("currency", key.currency().name()).update();
        }
    }

    Map<BalanceKey, Integer> lockBalances(List<BalanceKey> sortedKeys) {
        String tuples = sortedKeys.stream().map(key -> "(?, ?)").collect(Collectors.joining(", "));
        var statement = jdbc.sql("select student_id, currency, amount from ledger_balance where (student_id, currency) in ("
                + tuples + ") order by student_id, currency for update");
        for (var key : sortedKeys) {
            statement.param(key.studentId()).param(key.currency().name());
        }
        var balances = new LinkedHashMap<BalanceKey, Integer>();
        statement.query((rs, row) -> new BalanceRow(new BalanceKey(rs.getLong("student_id"),
                Currency.valueOf(rs.getString("currency"))), rs.getInt("amount")))
                .list().forEach(row -> balances.put(row.key(), row.amount()));
        return balances;
    }

    Optional<PostingResult.AlreadyPosted> existing(Posting posting) {
        return jdbc.sql("select id, amount, student_id, currency, kind from ledger_entry where idempotency_key = :key")
                .param("key", posting.idempotencyKey()).query((rs, row) -> {
                    if (rs.getLong("student_id") != posting.studentId()
                            || !rs.getString("currency").equals(posting.currency().name())
                            || !rs.getString("kind").equals(posting.kind().name())) {
                        throw new IllegalArgumentException("Idempotency key belongs to a different student, currency or kind");
                    }
                    return new PostingResult.AlreadyPosted(rs.getLong("id"), rs.getInt("amount"));
                }).optional();
    }

    long insert(Posting posting, int amount, Instant createdAt) {
        var source = posting.source();
        var author = posting.author();
        return jdbc.sql("""
                insert into ledger_entry (student_id, currency, kind, amount, source_type, source_id, source_title,
                                          author_id, author_name, idempotency_key, created_at)
                values (:student, :currency, :kind, :amount, :sourceType, :sourceId, :title,
                        :authorId, :authorName, :key, :createdAt) returning id
                """)
                .param("student", posting.studentId()).param("currency", posting.currency().name())
                .param("kind", posting.kind().name()).param("amount", amount).param("sourceType", source.type().name())
                .param("sourceId", source.id()).param("title", source.title())
                .param("authorId", author == null ? null : author.id()).param("authorName", author == null ? null : author.fullName())
                .param("key", posting.idempotencyKey()).param("createdAt", Timestamp.from(createdAt))
                .query(Long.class).single();
    }

    void saveBalances(Map<BalanceKey, Integer> balances) {
        balances.forEach((key, amount) -> jdbc.sql("""
                update ledger_balance set amount = :amount where student_id = :student and currency = :currency
                """).param("amount", amount).param("student", key.studentId()).param("currency", key.currency().name()).update());
    }

    List<BalanceRow> balances(Collection<Long> studentIds) {
        return jdbc.sql("select student_id, currency, amount from ledger_balance where student_id in (:ids)")
                .param("ids", studentIds).query((rs, row) -> new BalanceRow(new BalanceKey(rs.getLong("student_id"),
                        Currency.valueOf(rs.getString("currency"))), rs.getInt("amount"))).list();
    }

    List<Entry> history(long studentId, Optional<Currency> currency, Optional<LedgerCursor> cursor, int limit) {
        var params = new LinkedHashMap<String, Object>();
        params.put("student", studentId);
        String where = "student_id = :student";
        if (currency.isPresent()) {
            where += " and currency = :currency";
            params.put("currency", currency.get().name());
        }
        return entries(where, params, cursor, limit);
    }

    List<Entry> awards(Optional<LedgerCursor> cursor, int limit) {
        return entries("kind = 'ACTIVITY_AWARD'", new LinkedHashMap<>(), cursor, limit);
    }

    private List<Entry> entries(String where, Map<String, Object> params, Optional<LedgerCursor> cursor, int limit) {
        if (cursor.isPresent()) {
            where += " and (created_at, id) < (:createdAt, :id)";
            params.put("createdAt", Timestamp.from(cursor.get().createdAt()));
            params.put("id", cursor.get().id());
        }
        params.put("limit", limit);
        return jdbc.sql("select * from ledger_entry where " + where + " order by created_at desc, id desc limit :limit")
                .params(params).query((rs, row) -> new Entry(rs.getLong("student_id"), entryView(rs))).list();
    }

    List<BalanceMismatch> reconciliationPairs() {
        return jdbc.sql("""
                with totals as (
                    select student_id, currency, sum(amount) as amount from ledger_entry group by student_id, currency
                )
                select coalesce(b.student_id, t.student_id) student_id, coalesce(b.currency, t.currency) currency,
                       coalesce(b.amount, 0) balance, coalesce(t.amount, 0) ledger_sum
                from ledger_balance b full outer join totals t using (student_id, currency)
                order by student_id, currency
                """).query((rs, row) -> new BalanceMismatch(rs.getLong("student_id"),
                        Currency.valueOf(rs.getString("currency")), rs.getInt("balance"), rs.getInt("ledger_sum"))).list();
    }

    private static LedgerEntryView entryView(ResultSet rs) throws SQLException {
        Long authorId = rs.getObject("author_id", Long.class);
        var source = new Source(SourceType.valueOf(rs.getString("source_type")), rs.getObject("source_id", Long.class),
                rs.getString("source_title"));
        var author = authorId == null ? null : new Author(authorId, rs.getString("author_name"));
        return new LedgerEntryView(rs.getLong("id"), rs.getTimestamp("created_at").toInstant(),
                Currency.valueOf(rs.getString("currency")), rs.getInt("amount"), EntryKind.valueOf(rs.getString("kind")), source, author);
    }
}

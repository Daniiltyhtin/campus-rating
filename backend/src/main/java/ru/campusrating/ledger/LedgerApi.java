package ru.campusrating.ledger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface LedgerApi {

    /** Только внутри транзакции вызывающего (MANDATORY). Результаты — в порядке входа. */
    List<PostingResult> post(List<Posting> postings);

    /** Нет строки — 0, 0, 0. */
    Balances balances(long studentId);

    /** Ключ — каждый запрошенный id. */
    Map<Long, Balances> balances(Collection<Long> studentIds);

    LedgerPage<LedgerEntryView> history(long studentId, Optional<Currency> currency,
                                        Optional<String> cursor, int limit);

    /** Только ACTIVITY_AWARD. */
    LedgerPage<AwardEntryView> awards(Optional<String> cursor, int limit);

    ReconciliationResult reconcile();
}

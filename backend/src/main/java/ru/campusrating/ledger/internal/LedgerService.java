package ru.campusrating.ledger.internal;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.ledger.AwardEntryView;
import ru.campusrating.ledger.Balances;
import ru.campusrating.ledger.Currency;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.ledger.LedgerEntryView;
import ru.campusrating.ledger.LedgerPage;
import ru.campusrating.ledger.Posting;
import ru.campusrating.ledger.PostingResult;
import ru.campusrating.ledger.ReconciliationResult;
import ru.campusrating.ledger.domain.BalanceRules;
import ru.campusrating.ledger.domain.LedgerCursor;
import ru.campusrating.ledger.domain.PostingRules;
import ru.campusrating.ledger.internal.LedgerRepository.BalanceKey;
import ru.campusrating.ledger.internal.LedgerRepository.Entry;
import ru.campusrating.shared.error.FieldError;
import ru.campusrating.shared.error.ValidationFailedException;

@Service
public class LedgerService implements LedgerApi {
    private final LedgerRepository repository;
    private final Clock clock;

    LedgerService(LedgerRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PostingResult> post(List<Posting> postings) {
        postings.forEach(PostingRules::validate);
        var keys = postings.stream().map(posting -> new BalanceKey(posting.studentId(), posting.currency())).distinct()
                .sorted(Comparator.comparingLong(BalanceKey::studentId).thenComparing(key -> key.currency().name())).toList();
        if (keys.isEmpty()) {
            return List.of();
        }
        repository.ensureBalances(keys);
        var balances = repository.lockBalances(keys);
        var results = new ArrayList<PostingResult>(postings.size());
        for (var posting : postings) {
            results.add(postOne(posting, balances));
        }
        repository.saveBalances(balances);
        return List.copyOf(results);
    }

    private PostingResult postOne(Posting posting, Map<BalanceKey, Integer> balances) {
        var existing = repository.existing(posting.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }
        var key = new BalanceKey(posting.studentId(), posting.currency());
        int current = balances.get(key);
        int amount = BalanceRules.actualAmount(posting.currency(), current, posting.nominalAmount());
        if (amount == 0) {
            return new PostingResult.Zero();
        }
        int after = Math.addExact(current, amount);
        long id = repository.insert(posting, amount, clock.instant().truncatedTo(ChronoUnit.MICROS));
        balances.put(key, after);
        return new PostingResult.Posted(id, amount, after);
    }

    @Override
    public Balances balances(long studentId) {
        return balances(List.of(studentId)).get(studentId);
    }

    @Override
    public Map<Long, Balances> balances(Collection<Long> studentIds) {
        if (studentIds.isEmpty()) {
            return Map.of();
        }
        var amounts = new LinkedHashMap<Long, EnumMap<Currency, Integer>>();
        studentIds.forEach(id -> amounts.putIfAbsent(id, new EnumMap<>(Currency.class)));
        repository.balances(studentIds).forEach(row -> amounts.get(row.key().studentId()).put(row.key().currency(), row.amount()));
        var result = new LinkedHashMap<Long, Balances>();
        amounts.forEach((id, values) -> result.put(id, new Balances(values.getOrDefault(Currency.AR, 0),
                values.getOrDefault(Currency.BUA, 0), values.getOrDefault(Currency.BOA, 0))));
        return Map.copyOf(result);
    }

    @Override
    public LedgerPage<LedgerEntryView> history(long studentId, Optional<Currency> currency, Optional<String> cursor, int limit) {
        requireLimit(limit);
        return page(repository.history(studentId, currency, decode(cursor), limit + 1), limit, Entry::view);
    }

    @Override
    public LedgerPage<AwardEntryView> awards(Optional<String> cursor, int limit) {
        requireLimit(limit);
        return page(repository.awards(decode(cursor), limit + 1), limit, entry -> {
            var view = entry.view();
            return new AwardEntryView(view.id(), view.createdAt(), entry.studentId(), view.amount(),
                    view.currency(), view.source(), view.author());
        });
    }

    @Override
    public ReconciliationResult reconcile() {
        var checkedAt = clock.instant();
        var pairs = repository.reconciliationPairs();
        return new ReconciliationResult(checkedAt, pairs.size(),
                pairs.stream().filter(pair -> pair.balance() != pair.ledgerSum()).toList());
    }

    private Optional<LedgerCursor> decode(Optional<String> cursor) {
        try {
            return cursor.map(LedgerCursor::decode);
        } catch (IllegalArgumentException exception) {
            throw new ValidationFailedException("Неверный курсор", List.of(new FieldError("cursor", "INVALID", "Неверный формат курсора")));
        }
    }

    private void requireLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new ValidationFailedException("Неверный размер страницы",
                    List.of(new FieldError("limit", "INVALID", "Размер страницы должен быть от 1 до 100")));
        }
    }

    private <T> LedgerPage<T> page(List<Entry> entries, int limit, Function<Entry, T> mapper) {
        var visible = entries.subList(0, Math.min(limit, entries.size()));
        String next = null;
        if (entries.size() > limit) {
            var last = visible.getLast().view();
            next = new LedgerCursor(last.createdAt(), last.id()).encode();
        }
        return new LedgerPage<>(visible.stream().map(mapper).toList(), next);
    }
}

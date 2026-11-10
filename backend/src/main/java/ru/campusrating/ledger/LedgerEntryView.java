package ru.campusrating.ledger;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record LedgerEntryView(long id, Instant createdAt, Currency currency, int amount, EntryKind kind,
                              Source source, @Nullable Author author) {}

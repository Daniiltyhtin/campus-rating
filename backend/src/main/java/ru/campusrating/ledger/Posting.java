package ru.campusrating.ledger;

import org.jspecify.annotations.Nullable;

public record Posting(long studentId, Currency currency, EntryKind kind, int nominalAmount,
                      Source source, @Nullable Author author, String idempotencyKey) {}

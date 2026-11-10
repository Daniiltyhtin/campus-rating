package ru.campusrating.ledger;

import java.time.Instant;

public record AwardEntryView(long entryId, Instant createdAt, long studentId, int amount,
                             Currency currency, Source source, Author author) {}

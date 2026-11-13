package ru.campusrating.activity;

import java.time.Instant;
import ru.campusrating.ledger.Currency;

public record PointsAwarded(long entryId, long studentId, int amount, Currency currency, long activityId,
                            String activityTitle, Instant occurredAt) {}

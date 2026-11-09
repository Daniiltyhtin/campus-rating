package ru.campusrating.ledger;

import java.time.Instant;
import java.util.List;

public record ReconciliationResult(Instant checkedAt, int checkedPairs, List<BalanceMismatch> mismatches) {}

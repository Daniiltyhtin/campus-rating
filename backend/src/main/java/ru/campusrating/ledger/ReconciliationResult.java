package ru.campusrating.ledger;

import java.time.Instant;
import java.util.List;

@io.swagger.v3.oas.annotations.media.Schema(requiredProperties = {"checkedAt", "checkedPairs", "mismatches"})
public record ReconciliationResult(Instant checkedAt, @jakarta.validation.constraints.Min(0) int checkedPairs,
                                   List<BalanceMismatch> mismatches) {}

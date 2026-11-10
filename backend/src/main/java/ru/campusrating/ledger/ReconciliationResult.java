package ru.campusrating.ledger;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;

@Schema(requiredProperties = {"checkedAt", "checkedPairs", "mismatches"})
public record ReconciliationResult(Instant checkedAt, @Min(0) int checkedPairs,
                                   List<BalanceMismatch> mismatches) {}

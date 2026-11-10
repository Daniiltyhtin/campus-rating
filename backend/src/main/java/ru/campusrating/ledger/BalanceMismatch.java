package ru.campusrating.ledger;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;

@Schema(requiredProperties = {"studentId", "currency", "balance", "ledgerSum"})
public record BalanceMismatch(@Min(1) long studentId, Currency currency, int balance, int ledgerSum) {}

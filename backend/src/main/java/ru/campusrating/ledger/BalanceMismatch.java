package ru.campusrating.ledger;

@io.swagger.v3.oas.annotations.media.Schema(requiredProperties = {"studentId", "currency", "balance", "ledgerSum"})
public record BalanceMismatch(@jakarta.validation.constraints.Min(1) long studentId, Currency currency, int balance, int ledgerSum) {}

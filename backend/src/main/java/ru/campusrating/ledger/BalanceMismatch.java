package ru.campusrating.ledger;

public record BalanceMismatch(long studentId, Currency currency, int balance, int ledgerSum) {}

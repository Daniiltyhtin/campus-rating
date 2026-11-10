package ru.campusrating.ledger.domain;

import ru.campusrating.ledger.Currency;

public final class BalanceRules {
    private BalanceRules() {}

    public static int actualAmount(Currency currency, int balance, int nominalAmount) {
        if (currency != Currency.AR) {
            return nominalAmount;
        }
        return (int) (Math.clamp((long) balance + nominalAmount, 0, 1000) - balance);
    }
}

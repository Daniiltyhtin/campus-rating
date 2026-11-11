package ru.campusrating.activity.domain;

import ru.campusrating.ledger.Currency;

public enum ActivityType {
    ACADEMIC(Currency.BUA),
    SOCIAL(Currency.BOA);

    private final Currency currency;

    ActivityType(Currency currency) {
        this.currency = currency;
    }

    public Currency currency() {
        return currency;
    }
}

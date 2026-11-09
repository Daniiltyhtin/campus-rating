package ru.campusrating.ledger;

public sealed interface PostingResult {
    record Posted(long entryId, int amount, int balanceAfter) implements PostingResult {}

    /** Тот же ключ: исходная проводка, баланс не менялся. */
    record AlreadyPosted(long entryId, int amount) implements PostingResult {}

    /** Срезано до 0, проводка не записана. */
    record Zero() implements PostingResult {}
}

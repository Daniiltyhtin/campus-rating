package ru.campusrating.ledger.domain;

import ru.campusrating.ledger.Currency;
import ru.campusrating.ledger.EntryKind;
import ru.campusrating.ledger.Posting;
import ru.campusrating.ledger.SourceType;

public final class PostingRules {
    private PostingRules() {}

    public static void validate(Posting posting) {
        requireStructure(posting);
        requireSign(posting);
        requireSourceAndAuthor(posting);
        if (posting.kind() == EntryKind.ACTIVITY_AWARD) {
            requireAward(posting);
        } else if (posting.currency() != Currency.AR) {
            throw new IllegalArgumentException("Only activity awards use BUA or BOA");
        }
        if (posting.kind() == EntryKind.PENALTY && posting.source().id() == null) {
            throw new IllegalArgumentException("Penalty requires source id");
        }
    }

    private static void requireStructure(Posting posting) {
        if (posting == null || posting.currency() == null || posting.kind() == null || posting.source() == null
                || posting.source().type() == null || posting.source().title() == null || posting.idempotencyKey() == null) {
            throw new IllegalArgumentException("Posting fields must be present");
        }
    }

    private static void requireSign(Posting posting) {
        boolean valid = posting.kind() == EntryKind.PENALTY ? posting.nominalAmount() < 0 : posting.nominalAmount() > 0;
        if (!valid) {
            throw new IllegalArgumentException("Posting amount has wrong sign");
        }
    }

    private static void requireSourceAndAuthor(Posting posting) {
        var expected = switch (posting.kind()) {
            case INITIAL -> SourceType.REGISTRATION;
            case SEMESTER_ACCRUAL -> SourceType.SEMESTER;
            case PENALTY -> SourceType.CHECKPOINT;
            case ACTIVITY_AWARD -> SourceType.ACTIVITY;
        };
        if (posting.source().type() != expected) {
            throw new IllegalArgumentException("Source type does not match posting kind");
        }
        if (posting.kind() != EntryKind.ACTIVITY_AWARD && posting.author() != null) {
            throw new IllegalArgumentException("System postings must not have an author");
        }
    }

    private static void requireAward(Posting posting) {
        if (posting.currency() == Currency.AR || posting.author() == null || posting.source().id() == null) {
            throw new IllegalArgumentException("Award requires BUA or BOA, author and source id");
        }
        if (posting.author().fullName() == null || posting.author().fullName().isBlank()) {
            throw new IllegalArgumentException("Award requires author's full name");
        }
    }
}

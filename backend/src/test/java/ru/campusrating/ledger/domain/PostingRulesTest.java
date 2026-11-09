package ru.campusrating.ledger.domain;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.campusrating.ledger.Author;
import ru.campusrating.ledger.Currency;
import ru.campusrating.ledger.EntryKind;
import ru.campusrating.ledger.Posting;
import ru.campusrating.ledger.Source;
import ru.campusrating.ledger.SourceType;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PostingRulesTest {
    private static final Source SOURCE = new Source(SourceType.ACTIVITY, 42L, "Название");
    private static final Author AUTHOR = new Author(2, "Преподаватель");

    @ParameterizedTest
    @MethodSource("validPostings")
    void acceptsValidPostings(Posting posting) {
        assertThatCode(() -> PostingRules.validate(posting)).doesNotThrowAnyException();
    }

    static Stream<Posting> validPostings() {
        return Stream.of(
                posting(Currency.AR, EntryKind.INITIAL, 1000, new Source(SourceType.REGISTRATION, null, "Регистрация"), null),
                posting(Currency.AR, EntryKind.SEMESTER_ACCRUAL, 200, SOURCE, null),
                posting(Currency.AR, EntryKind.PENALTY, -20, SOURCE, null),
                posting(Currency.BUA, EntryKind.ACTIVITY_AWARD, 50, SOURCE, AUTHOR),
                posting(Currency.BOA, EntryKind.ACTIVITY_AWARD, 50, SOURCE, AUTHOR));
    }

    @ParameterizedTest
    @MethodSource("invalidPostings")
    void rejectsInvalidPostings(Posting posting) {
        assertThatIllegalArgumentException().isThrownBy(() -> PostingRules.validate(posting));
    }

    static Stream<Posting> invalidPostings() {
        return Stream.concat(Stream.of(
                null,
                posting(null, EntryKind.INITIAL, 1000, SOURCE, null),
                posting(Currency.AR, null, 1000, SOURCE, null),
                posting(Currency.AR, EntryKind.INITIAL, 1000, null, null),
                posting(Currency.AR, EntryKind.INITIAL, 1000, new Source(null, null, "Title"), null),
                posting(Currency.AR, EntryKind.INITIAL, 1000, new Source(SourceType.REGISTRATION, null, null), null),
                new Posting(1, Currency.AR, EntryKind.INITIAL, 1000, SOURCE, null, null),
                posting(Currency.AR, EntryKind.PENALTY, 20, SOURCE, null),
                posting(Currency.AR, EntryKind.PENALTY, 0, SOURCE, null),
                posting(Currency.AR, EntryKind.PENALTY, -20, new Source(SourceType.CHECKPOINT, null, "Точка"), null),
                posting(Currency.BOA, EntryKind.ACTIVITY_AWARD, 50, SOURCE, null),
                posting(Currency.BUA, EntryKind.ACTIVITY_AWARD, 50, new Source(SourceType.ACTIVITY, null, "Событие"), AUTHOR)),
                Stream.of(EntryKind.values()).flatMap(kind -> {
                    var invalidCurrency = kind == EntryKind.ACTIVITY_AWARD ? Currency.AR : Currency.BOA;
                    return Stream.of(posting(invalidCurrency, kind, 20, SOURCE, AUTHOR),
                            posting(Currency.AR, kind, 0, SOURCE, AUTHOR),
                            posting(Currency.AR, kind, kind == EntryKind.PENALTY ? 1 : -1, SOURCE, AUTHOR));
                }));
    }

    private static Posting posting(Currency currency, EntryKind kind, int amount, Source source, Author author) {
        return new Posting(1, currency, kind, amount, source, author, "key");
    }
}

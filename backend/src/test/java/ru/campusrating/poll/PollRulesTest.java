package ru.campusrating.poll;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import ru.campusrating.poll.domain.PollDraft;
import ru.campusrating.poll.domain.PollQuestionType;
import ru.campusrating.poll.domain.PollRules;

import static org.assertj.core.api.Assertions.assertThat;

class PollRulesTest {
    private static final Instant NOW = Instant.parse("2026-11-18T09:00:00Z");
    private static final Instant ENDS = Instant.parse("2026-11-20T15:00:00Z");

    @Test
    void validDraftHasNoViolations() {
        assertThat(PollRules.violations(draft(UnaryOperator.identity()), NOW)).isEmpty();
    }

    @Test
    void endMustBeStrictlyInFuture() {
        assertThat(PollRules.violations(draft(d -> withEnd(d, NOW.minusSeconds(1))), NOW)).containsExactly("endsAt");
        assertThat(PollRules.violations(draft(d -> withEnd(d, NOW)), NOW)).containsExactly("endsAt");
        assertThat(PollRules.violations(draft(d -> withEnd(d, NOW.plusNanos(1000))), NOW)).isEmpty();
    }

    @Test
    void titleIsOneToTwoHundredCharacters() {
        assertThat(PollRules.violations(draft(d -> withTitle(d, "")), NOW)).containsExactly("title");
        assertThat(PollRules.violations(draft(d -> withTitle(d, "Т")), NOW)).isEmpty();
        assertThat(PollRules.violations(draft(d -> withTitle(d, "Т".repeat(200))), NOW)).isEmpty();
        assertThat(PollRules.violations(draft(d -> withTitle(d, "Т".repeat(201))), NOW)).containsExactly("title");
    }

    @Test
    void descriptionIsUpToTwoThousandCharacters() {
        assertThat(PollRules.violations(draft(d -> withDescription(d, "")), NOW)).isEmpty();
        assertThat(PollRules.violations(draft(d -> withDescription(d, "О".repeat(2000))), NOW)).isEmpty();
        assertThat(PollRules.violations(draft(d -> withDescription(d, "О".repeat(2001))), NOW)).containsExactly("description");
    }

    @Test
    void groupsAreNonEmptyWithoutRepeats() {
        assertThat(PollRules.violations(draft(d -> withGroups(d, List.of())), NOW)).containsExactly("groupIds");
        assertThat(PollRules.violations(draft(d -> withGroups(d, List.of(11L, 11L))), NOW)).containsExactly("groupIds");
        assertThat(PollRules.violations(draft(d -> withGroups(d, List.of(11L, 12L))), NOW)).isEmpty();
    }

    @Test
    void pollHasOneToTwentyQuestions() {
        assertThat(PollRules.violations(draft(d -> withQuestions(d, List.of())), NOW)).containsExactly("questions");
        assertThat(PollRules.violations(draft(d -> withQuestions(d, questions(1))), NOW)).isEmpty();
        assertThat(PollRules.violations(draft(d -> withQuestions(d, questions(20))), NOW)).isEmpty();
        assertThat(PollRules.violations(draft(d -> withQuestions(d, questions(21))), NOW)).containsExactly("questions");
    }

    @Test
    void questionTextIsOneToFiveHundredCharacters() {
        assertThat(violationsOf(question("", "Утро", "Вечер"))).containsExactly("questions[0].text");
        assertThat(violationsOf(question("В".repeat(500), "Утро", "Вечер"))).isEmpty();
        assertThat(violationsOf(question("В".repeat(501), "Утро", "Вечер"))).containsExactly("questions[0].text");
    }

    @Test
    void questionHasTwoToTenOptions() {
        assertThat(violationsOf(question("Когда?", "Утро"))).containsExactly("questions[0].options");
        assertThat(violationsOf(question("Когда?", options(2)))).isEmpty();
        assertThat(violationsOf(question("Когда?", options(10)))).isEmpty();
        assertThat(violationsOf(question("Когда?", options(11)))).containsExactly("questions[0].options");
    }

    @Test
    void optionsAreUniqueAfterTrimWithinQuestionOnly() {
        assertThat(violationsOf(question("Когда?", "Утро", " Утро "))).containsExactly("questions[0].options");
        var twoQuestions = draft(d -> withQuestions(d, List.of(
                new PollDraft.Question("Первый", PollQuestionType.SINGLE, List.of("Да", "Нет")),
                new PollDraft.Question("Второй", PollQuestionType.MULTIPLE, List.of("Да", "Нет")))));
        assertThat(PollRules.violations(twoQuestions, NOW)).isEmpty();
        var secondBroken = draft(d -> withQuestions(d, List.of(
                new PollDraft.Question("Первый", PollQuestionType.SINGLE, List.of("Да", "Нет")),
                new PollDraft.Question("Второй", PollQuestionType.MULTIPLE, List.of("Да", "Да ")))));
        assertThat(PollRules.violations(secondBroken, NOW)).containsExactly("questions[1].options");
    }

    @Test
    void optionTextIsOneToTwoHundredCharacters() {
        assertThat(violationsOf(question("Когда?", "Утро", ""))).singleElement().asString().startsWith("questions[0].options");
        assertThat(violationsOf(question("Когда?", "Утро", "В".repeat(200)))).isEmpty();
        assertThat(violationsOf(question("Когда?", "Утро", "В".repeat(201)))).singleElement().asString()
                .startsWith("questions[0].options");
    }

    @Test
    void pollIsOpenStrictlyBeforeEnd() {
        assertThat(PollRules.open(ENDS, ENDS.minusNanos(1000))).isTrue();
        assertThat(PollRules.open(ENDS, ENDS)).isFalse();
        assertThat(PollRules.open(ENDS, ENDS.plusSeconds(1))).isFalse();
    }

    private static List<String> violationsOf(PollDraft.Question question) {
        return PollRules.violations(draft(d -> withQuestions(d, List.of(question))), NOW);
    }

    private static PollDraft.Question question(String text, String... options) {
        return new PollDraft.Question(text, PollQuestionType.SINGLE, List.of(options));
    }

    private static String[] options(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(index -> "Вариант " + index).toArray(String[]::new);
    }

    private static List<PollDraft.Question> questions(int count) {
        var result = new ArrayList<PollDraft.Question>();
        for (int index = 1; index <= count; index++) {
            result.add(new PollDraft.Question("Вопрос " + index,
                    index % 2 == 0 ? PollQuestionType.MULTIPLE : PollQuestionType.SINGLE, List.of(options(2))));
        }
        return result;
    }

    private static PollDraft draft(UnaryOperator<PollDraft> change) {
        return change.apply(new PollDraft("Формат зачёта", "Выберите удобное время.", ENDS, List.of(11L), true,
                List.of(new PollDraft.Question("Когда удобнее?", PollQuestionType.SINGLE, List.of("Утро", "Вечер")))));
    }

    private static PollDraft withEnd(PollDraft d, Instant endsAt) {
        return new PollDraft(d.title(), d.description(), endsAt, d.groupIds(), d.anonymous(), d.questions());
    }

    private static PollDraft withTitle(PollDraft d, String title) {
        return new PollDraft(title, d.description(), d.endsAt(), d.groupIds(), d.anonymous(), d.questions());
    }

    private static PollDraft withDescription(PollDraft d, String description) {
        return new PollDraft(d.title(), description, d.endsAt(), d.groupIds(), d.anonymous(), d.questions());
    }

    private static PollDraft withGroups(PollDraft d, List<Long> groups) {
        return new PollDraft(d.title(), d.description(), d.endsAt(), groups, d.anonymous(), d.questions());
    }

    private static PollDraft withQuestions(PollDraft d, List<PollDraft.Question> questions) {
        return new PollDraft(d.title(), d.description(), d.endsAt(), d.groupIds(), d.anonymous(), questions);
    }
}

package ru.campusrating.poll;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.campusrating.poll.domain.Ballot;
import ru.campusrating.poll.domain.PollQuestionType;
import ru.campusrating.poll.domain.VoteValidation;
import ru.campusrating.poll.domain.VoteValidation.Answer;
import ru.campusrating.poll.domain.VoteValidation.Question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoteValidationTest {
    private static final Question WHEN = new Question(811, PollQuestionType.SINGLE, List.of(821L, 822L));
    private static final Question FORMAT = new Question(812, PollQuestionType.MULTIPLE, List.of(831L, 832L, 833L));
    private static final List<Question> POLL = List.of(WHEN, FORMAT);

    @Test
    void singleWithOneOptionAndMultipleWithSeveralMakeBallot() {
        Ballot ballot = VoteValidation.validate(POLL, List.of(answer(811, 822), answer(812, 831, 833)));
        assertThat(ballot.optionIds()).containsExactlyInAnyOrder(822L, 831L, 833L);
    }

    @Test
    void multipleAcceptsOneOptionAndAllOptions() {
        assertThat(VoteValidation.validate(POLL, List.of(answer(811, 821), answer(812, 832))).optionIds())
                .containsExactlyInAnyOrder(821L, 832L);
        assertThat(VoteValidation.validate(POLL, List.of(answer(811, 821), answer(812, 831, 832, 833))).optionIds())
                .containsExactlyInAnyOrder(821L, 831L, 832L, 833L);
    }

    @Test
    void answersMayComeInAnyOrder() {
        assertThat(VoteValidation.validate(POLL, List.of(answer(812, 833), answer(811, 821))).optionIds())
                .containsExactlyInAnyOrder(821L, 833L);
    }

    @Test
    void singleWithTwoOptionsIsRejected() {
        rejected(List.of(answer(811, 821, 822), answer(812, 831)), "answers[0].optionIds");
    }

    @Test
    void emptyChoiceIsRejected() {
        rejected(List.of(answer(811, 821), answer(812)), "answers[1].optionIds");
        rejected(List.of(answer(811), answer(812, 831)), "answers[0].optionIds");
    }

    @Test
    void missingQuestionIsRejected() {
        rejected(List.of(answer(811, 821)), "answers");
        rejected(List.of(), "answers");
    }

    @Test
    void optionOfAnotherQuestionIsRejected() {
        rejected(List.of(answer(811, 831), answer(812, 832)), "answers[0].optionIds");
        rejected(List.of(answer(811, 821), answer(812, 831, 822)), "answers[1].optionIds");
    }

    @Test
    void unknownOptionIsRejected() {
        rejected(List.of(answer(811, 999), answer(812, 831)), "answers[0].optionIds");
    }

    @Test
    void repeatedOptionIsRejected() {
        rejected(List.of(answer(811, 821), answer(812, 831, 831)), "answers[1].optionIds");
        rejected(List.of(answer(811, 821, 821), answer(812, 831)), "answers[0].optionIds");
    }

    @Test
    void questionAnsweredTwiceIsRejected() {
        rejected(List.of(answer(811, 821), answer(811, 822), answer(812, 831)), "answers[1].questionId");
    }

    @Test
    void unknownQuestionIsRejected() {
        rejected(List.of(answer(999, 821), answer(811, 821), answer(812, 831)), "answers[0].questionId");
    }

    private static void rejected(List<Answer> answers, String field) {
        assertThatThrownBy(() -> VoteValidation.validate(POLL, answers))
                .isInstanceOfSatisfying(VoteValidation.Rejected.class,
                        rejection -> assertThat(rejection.field()).isEqualTo(field));
    }

    private static Answer answer(long questionId, long... optionIds) {
        return new Answer(questionId, Arrays.stream(optionIds).boxed().toList());
    }
}

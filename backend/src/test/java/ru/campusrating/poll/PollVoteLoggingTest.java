package ru.campusrating.poll;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.campusrating.poll.api.PollController.PollAnswer;
import ru.campusrating.poll.api.PollController.PollVote;

import static org.assertj.core.api.Assertions.assertThat;

class PollVoteLoggingTest {
    @Test
    void requestStringsDoNotExposeChoices() {
        var answer = new PollAnswer(17L, List.of(987654L, 876543L));
        for (var value : List.of(answer.toString(), new PollVote(List.of(answer)).toString())) {
            assertThat(value).doesNotContain("987654", "876543", "optionIds");
        }
        assertThat(answer.toString()).contains("questionId=17", "optionCount=2");
        assertThat(new PollVote(List.of(answer)).toString()).isEqualTo("PollVote[answerCount=1]");
    }

    @Test
    void invalidRequestsCanBeLoggedWithoutThrowing() {
        assertThat(new PollAnswer(null, null).toString()).doesNotContain("optionIds");
        assertThat(new PollVote(null).toString()).doesNotContain("optionIds");
    }
}

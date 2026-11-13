package ru.campusrating.poll.domain;

import java.time.Instant;
import java.util.List;

public record PollDraft(String title, String description, Instant endsAt, List<Long> groupIds, boolean anonymous,
                        List<Question> questions) {
    public PollDraft {
        groupIds = List.copyOf(groupIds);
        questions = List.copyOf(questions);
    }

    public record Question(String text, PollQuestionType type, List<String> options) {
        public Question {
            options = List.copyOf(options);
        }
    }
}

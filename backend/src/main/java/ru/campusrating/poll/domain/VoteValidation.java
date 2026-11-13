package ru.campusrating.poll.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class VoteValidation {
    public record Question(long id, PollQuestionType type, List<Long> optionIds) {
        public Question {
            optionIds = List.copyOf(optionIds);
        }
    }

    public record Answer(long questionId, List<Long> optionIds) {
        public Answer {
            optionIds = List.copyOf(optionIds);
        }
    }

    public static final class Rejected extends RuntimeException {
        private final String field;

        Rejected(String field) {
            super("Недопустимый ответ: " + field, null, false, false);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    private VoteValidation() {}

    public static Ballot validate(List<Question> questions, List<Answer> answers) {
        var byId = index(questions);
        var answered = new HashSet<Long>();
        var chosen = new HashSet<Long>();
        for (int index = 0; index < answers.size(); index++) {
            var answer = answers.get(index);
            var question = byId.get(answer.questionId());
            if (question == null || !answered.add(answer.questionId())) throw new Rejected("answers[" + index + "].questionId");
            if (!valid(question, answer)) throw new Rejected("answers[" + index + "].optionIds");
            chosen.addAll(answer.optionIds());
        }
        if (answered.size() != questions.size()) throw new Rejected("answers");
        return new Ballot(chosen);
    }

    private static boolean valid(Question question, Answer answer) {
        var options = answer.optionIds();
        if (options.isEmpty() || new HashSet<>(options).size() != options.size()) return false;
        if (question.type() == PollQuestionType.SINGLE && options.size() != 1) return false;
        return question.optionIds().containsAll(options);
    }

    private static Map<Long, Question> index(List<Question> questions) {
        var result = new HashMap<Long, Question>();
        questions.forEach(question -> result.put(question.id(), question));
        return result;
    }
}

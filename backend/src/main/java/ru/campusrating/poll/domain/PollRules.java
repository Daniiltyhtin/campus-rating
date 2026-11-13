package ru.campusrating.poll.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class PollRules {
    public static final int MAX_TITLE = 200;
    public static final int MAX_DESCRIPTION = 2000;
    public static final int MAX_QUESTIONS = 20;
    public static final int MAX_QUESTION_TEXT = 500;
    public static final int MIN_OPTIONS = 2;
    public static final int MAX_OPTIONS = 10;
    public static final int MAX_OPTION_TEXT = 200;

    private PollRules() {}

    public static List<String> violations(PollDraft draft, Instant now) {
        var result = new ArrayList<String>();
        if (!text(draft.title(), MAX_TITLE)) result.add("title");
        if (draft.description().length() > MAX_DESCRIPTION) result.add("description");
        if (!now.isBefore(draft.endsAt())) result.add("endsAt");
        if (draft.groupIds().isEmpty() || new HashSet<>(draft.groupIds()).size() != draft.groupIds().size()) result.add("groupIds");
        if (draft.questions().isEmpty() || draft.questions().size() > MAX_QUESTIONS) {
            result.add("questions");
            return result;
        }
        for (int index = 0; index < draft.questions().size(); index++) {
            question(draft.questions().get(index), "questions[" + index + "]", result);
        }
        return result;
    }

    public static boolean open(Instant endsAt, Instant now) {
        return now.isBefore(endsAt);
    }

    private static void question(PollDraft.Question question, String path, List<String> result) {
        if (!text(question.text(), MAX_QUESTION_TEXT)) result.add(path + ".text");
        var options = question.options();
        if (options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
            result.add(path + ".options");
            return;
        }
        for (int index = 0; index < options.size(); index++) {
            if (!text(options.get(index), MAX_OPTION_TEXT)) {
                result.add(path + ".options[" + index + "]");
                return;
            }
        }
        if (options.stream().map(String::strip).distinct().count() != options.size()) result.add(path + ".options");
    }

    private static boolean text(String value, int max) {
        return !value.isBlank() && value.length() <= max;
    }
}

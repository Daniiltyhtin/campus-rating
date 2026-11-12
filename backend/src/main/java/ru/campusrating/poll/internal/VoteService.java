package ru.campusrating.poll.internal;

import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.GroupMembershipState;
import ru.campusrating.poll.api.PollController.PollAnswer;
import ru.campusrating.poll.api.PollController.PollResults;
import ru.campusrating.poll.domain.PollRules;
import ru.campusrating.poll.domain.VoteValidation;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.FieldError;
import ru.campusrating.shared.error.ProblemCode;
import ru.campusrating.shared.error.ValidationFailedException;

@Service
public class VoteService {
    private final PollRepository repository;
    private final PollService polls;
    private final PollViews views;
    private final CurrentUser current;
    private final Clock clock;

    VoteService(PollRepository repository, PollService polls, PollViews views, CurrentUser current, Clock clock) {
        this.repository = repository;
        this.polls = polls;
        this.views = views;
        this.current = current;
        this.clock = clock;
    }

    @Transactional
    public PollResults vote(long pollId, List<PollAnswer> answers) {
        var user = current.require();
        var row = polls.require(pollId);
        polls.requireTarget(user, row);
        if (user.membership() == null || user.membership().state() != GroupMembershipState.ACTIVE) {
            throw new ApiException(ProblemCode.MEMBERSHIP_NOT_ACTIVE, "Голосовать можно после подтверждения членства");
        }
        var now = clock.instant();
        if (!PollRules.open(row.endsAt(), now)) throw new ApiException(ProblemCode.POLL_CLOSED, "Опрос завершён");
        var options = repository.options(pollId);
        var questions = repository.questions(pollId).stream().map(question -> new VoteValidation.Question(question.id(), question.type(),
                options.stream().filter(option -> option.questionId() == question.id()).map(PollRepository.OptionRow::id).toList()))
                .toList();
        var chosen = validate(questions, answers.stream()
                .map(answer -> new VoteValidation.Answer(answer.questionId(), answer.optionIds())).toList());
        if (!repository.participate(pollId, user.id(), now)) {
            throw new ApiException(ProblemCode.ALREADY_VOTED, "Вы уже проголосовали в этом опросе");
        }
        repository.count(chosen);
        if (!row.anonymous()) repository.name(pollId, user.id(), chosen);
        return views.results(pollId);
    }

    private static List<Long> validate(List<VoteValidation.Question> questions, List<VoteValidation.Answer> answers) {
        try {
            return List.copyOf(VoteValidation.validate(questions, answers).optionIds());
        } catch (VoteValidation.Rejected rejected) {
            throw new ValidationFailedException("Проверьте ответы",
                    List.of(new FieldError(rejected.field(), "INVALID", "Недопустимый ответ")));
        }
    }
}

package ru.campusrating.poll.internal;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.identity.AuthenticatedUser;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.Role;
import ru.campusrating.identity.StudentMember;
import ru.campusrating.identity.TeacherScope;
import ru.campusrating.poll.PollCreated;
import ru.campusrating.poll.api.PollController.Poll;
import ru.campusrating.poll.api.PollController.PollAnswer;
import ru.campusrating.poll.api.PollController.PollCreate;
import ru.campusrating.poll.api.PollController.PollResponse;
import ru.campusrating.poll.api.PollController.PollSummary;
import ru.campusrating.poll.api.PollController.UserRef;
import ru.campusrating.poll.domain.PollDraft;
import ru.campusrating.poll.domain.PollRules;
import ru.campusrating.poll.internal.PollRepository.PollRow;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.FieldError;
import ru.campusrating.shared.error.ProblemCode;
import ru.campusrating.shared.error.ValidationFailedException;

@Service
public class PollService {
    private final PollRepository repository;
    private final PollViews views;
    private final CurrentUser current;
    private final TeacherScope scope;
    private final GroupMembers members;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    PollService(PollRepository repository, PollViews views, CurrentUser current, TeacherScope scope, GroupMembers members,
                ApplicationEventPublisher publisher, Clock clock) {
        this.repository = repository;
        this.views = views;
        this.current = current;
        this.scope = scope;
        this.members = members;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Transactional
    public Poll create(PollCreate request) {
        var user = current.require();
        if (user.role() != Role.TEACHER) throw forbidden();
        var now = clock.instant();
        var draft = new PollDraft(request.title(), request.description(), request.endsAt(), request.groupIds(), request.anonymous(),
                request.questions().stream().map(question -> new PollDraft.Question(question.text(), question.type(),
                        question.options())).toList());
        var violations = PollRules.violations(draft, now);
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("Проверьте поля формы",
                    violations.stream().map(field -> new FieldError(field, "INVALID", "Недопустимое значение")).toList());
        }
        scope.requireAccessible(user.id(), draft.groupIds());
        long id = repository.insert(user.id(), draft.title(), draft.description(), draft.endsAt(), draft.anonymous(), now);
        draft.groupIds().forEach(group -> repository.addGroup(id, group));
        for (int position = 0; position < draft.questions().size(); position++) {
            var question = draft.questions().get(position);
            long questionId = repository.addQuestion(id, position, question.text(), question.type());
            for (int option = 0; option < question.options().size(); option++) {
                repository.addOption(questionId, option, question.options().get(option).strip());
            }
        }
        var students = views.eligible(draft.groupIds()).stream().map(StudentMember::studentId).distinct().toList();
        publisher.publishEvent(new PollCreated(id, draft.title(), draft.endsAt(), students, now));
        return views.poll(require(id), null, true);
    }

    @Transactional(readOnly = true)
    public List<PollSummary> list() {
        var user = current.require();
        var now = clock.instant();
        List<PollRow> rows = switch (user.role()) {
            case TEACHER -> repository.byAuthor(user.id());
            case STUDENT -> user.membership() == null ? List.of() : repository.byGroup(user.membership().groupId());
            case ADMIN -> throw forbidden();
        };
        var ids = rows.stream().map(PollRow::id).toList();
        var groups = new LinkedHashMap<Long, List<Long>>();
        repository.groups(ids).forEach(link -> groups.computeIfAbsent(link.pollId(), key -> new ArrayList<>()).add(link.groupId()));
        var voted = user.role() == Role.STUDENT ? new HashSet<>(repository.votedPolls(user.id(), ids)) : null;
        Comparator<PollRow> order = Comparator.comparing((PollRow row) -> !PollRules.open(row.endsAt(), now))
                .thenComparing((left, right) -> PollRules.open(left.endsAt(), now)
                        ? left.endsAt().compareTo(right.endsAt()) : right.endsAt().compareTo(left.endsAt()))
                .thenComparingLong(PollRow::id);
        return rows.stream().sorted(order).map(row -> new PollSummary(row.id(), row.title(), row.endsAt(),
                PollRules.open(row.endsAt(), now), row.anonymous(), voted == null ? null : voted.contains(row.id()),
                views.groupRefs(groups.getOrDefault(row.id(), List.of())))).toList();
    }

    @Transactional(readOnly = true)
    public Poll get(long pollId) {
        var user = current.require();
        var row = require(pollId);
        if (isAuthor(user, row)) return views.poll(row, null, true);
        requireTarget(user, row);
        boolean voted = !repository.votedPolls(user.id(), List.of(pollId)).isEmpty();
        return views.poll(row, voted, false);
    }

    @Transactional(readOnly = true)
    public List<PollResponse> responses(long pollId) {
        var user = current.require();
        var row = require(pollId);
        if (!isAuthor(user, row)) throw forbidden();
        if (row.anonymous()) throw new ApiException(ProblemCode.INVALID_STATE, "Анонимный опрос: ответы по студентам не сохраняются");
        var questionOf = new LinkedHashMap<Long, Long>();
        repository.options(pollId).forEach(option -> questionOf.put(option.id(), option.questionId()));
        var choices = new LinkedHashMap<Long, LinkedHashMap<Long, List<Long>>>();
        for (var choice : repository.namedChoices(pollId)) {
            choices.computeIfAbsent(choice.studentId(), key -> new LinkedHashMap<>())
                    .computeIfAbsent(questionOf.get(choice.optionId()), key -> new ArrayList<>()).add(choice.optionId());
        }
        var users = members.users(choices.keySet());
        var memberships = members.memberships(choices.keySet());
        var groups = members.groups(memberships.values().stream().map(StudentMember::groupId).distinct().toList());
        var questionOrder = repository.questions(pollId).stream().map(PollRepository.QuestionRow::id).toList();
        return choices.entrySet().stream().map(entry -> {
            long student = entry.getKey();
            var answers = questionOrder.stream().filter(entry.getValue()::containsKey)
                    .map(question -> new PollAnswer(question, entry.getValue().get(question).stream().sorted().toList())).toList();
            var group = groups.get(Objects.requireNonNull(memberships.get(student)).groupId());
            return new PollResponse(new UserRef(student, Objects.requireNonNull(users.get(student)).fullName()),
                    PollViews.groupRef(Objects.requireNonNull(group)), answers);
        }).sorted(Comparator.comparing((PollResponse response) -> response.student().fullName())
                .thenComparingLong(response -> response.student().id())).toList();
    }

    PollRow require(long pollId) {
        return repository.find(pollId).orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Опрос не найден"));
    }

    void requireTarget(AuthenticatedUser user, PollRow row) {
        if (user.role() != Role.STUDENT || user.membership() == null
                || !views.groupIds(row.id()).contains(user.membership().groupId())) throw forbidden();
    }

    private static boolean isAuthor(AuthenticatedUser user, PollRow row) {
        return user.role() == Role.TEACHER && user.id() == row.authorId();
    }

    static ApiException forbidden() {
        return new ApiException(ProblemCode.FORBIDDEN, "Нет доступа");
    }
}

package ru.campusrating.checkpoint.internal;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.checkpoint.CheckpointCreated;
import ru.campusrating.checkpoint.api.*;
import ru.campusrating.checkpoint.api.CheckpointView.*;
import ru.campusrating.identity.*;
import ru.campusrating.settings.SettingsApi;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

@Service
public class CheckpointService {
    private final CheckpointRepository repository;
    private final AssignmentReconciliation assignments;
    private final CurrentUser current;
    private final TeacherScope scope;
    private final GroupMembers members;
    private final SettingsApi settings;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;

    CheckpointService(CheckpointRepository repository, AssignmentReconciliation assignments, CurrentUser current,
                      TeacherScope scope, GroupMembers members, SettingsApi settings, Clock clock, ApplicationEventPublisher publisher) {
        this.repository = repository;
        this.assignments = assignments;
        this.current = current;
        this.scope = scope;
        this.members = members;
        this.settings = settings;
        this.clock = clock;
        this.publisher = publisher;
    }

    @Transactional public CheckpointView create(CheckpointCreate request) {
        var user = current.require();
        validateCreation(request);
        requireGroups(user.id(), request.groupIds());
        var interval = settings.penaltyInterval();
        if (interval.compareTo(settings.minPenaltyInterval()) < 0) {
            throw new ApiException(ProblemCode.INVALID_STATE, "Интервал штрафа меньше допустимого");
        }
        long id = repository.create(user.id(), request, settings.penaltyStep(), Math.toIntExact(interval.toSeconds()), clock.instant());
        request.groupIds().forEach(group -> repository.addGroup(id, group));
        assignments.checkpoint(id);
        publisher.publishEvent(new CheckpointCreated(id));
        return view(require(id));
    }

    @Transactional(readOnly = true) public List<CheckpointView> list() {
        return repository.byAuthor(current.require().id(), clock.instant()).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true) public CheckpointView get(long id) {
        var user = current.require();
        var checkpoint = require(id);
        if (user.role() != Role.ADMIN) requireAuthor(checkpoint, user);
        return view(checkpoint);
    }

    @Transactional public CheckpointView update(long id, CheckpointUpdate request) {
        var checkpoint = require(id);
        requireAuthor(checkpoint, current.require());
        if (request.getTitle() == null && request.getDescription() == null) {
            throw CheckpointValidation.invalid("body", "Укажите название или описание");
        }
        repository.update(id, request.getTitle(), request.getDescription(), clock.instant());
        return view(require(id));
    }

    private void validateCreation(CheckpointCreate request) {
        if (new HashSet<>(request.groupIds()).size() != request.groupIds().size()) {
            throw CheckpointValidation.invalid("groupIds", "Группы не должны повторяться");
        }
        if (!request.deadline().isAfter(clock.instant())) {
            throw CheckpointValidation.invalid("deadline", "Дедлайн должен быть в будущем");
        }
    }

    private void requireGroups(long teacherId, List<Long> groups) {
        try {
            scope.requireAccessible(teacherId, groups);
        } catch (ApiException exception) {
            if (exception.code() != ProblemCode.GROUP_NOT_ACCESSIBLE) throw exception;
            var available = scope.accessibleGroupIds(teacherId);
            long first = groups.stream().filter(id -> !available.contains(id)).findFirst().orElseThrow(() -> exception);
            var group = members.groups(List.of(first)).get(first);
            String name = group == null ? Long.toString(first) : group.name();
            throw new ApiException(ProblemCode.GROUP_NOT_ACCESSIBLE, "Группа " + name + " вам недоступна");
        }
    }

    private CheckpointData require(long id) {
        return repository.find(id).orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Точка не найдена"));
    }

    private void requireAuthor(CheckpointData checkpoint, AuthenticatedUser user) {
        if (user.role() != Role.TEACHER || checkpoint.authorId() != user.id()) {
            throw new ApiException(ProblemCode.FORBIDDEN, "Точка принадлежит другому преподавателю");
        }
    }

    private CheckpointView view(CheckpointData checkpoint) {
        var groupIds = repository.groups(checkpoint.id());
        var groups = members.groups(groupIds);
        var author = members.users(List.of(checkpoint.authorId())).get(checkpoint.authorId());
        return new CheckpointView(checkpoint.id(), checkpoint.title(), checkpoint.description(), checkpoint.deadline(),
                checkpoint.cost(), checkpoint.penaltyStep(), checkpoint.penaltyIntervalSeconds(),
                groupIds.stream().map(id -> groupView(groups.get(id))).toList(), new UserView(author.id(), author.fullName()),
                checkpoint.createdAt(), checkpoint.submittedCount(), checkpoint.totalCount());
    }

    private StudyGroupView groupView(GroupRef group) {
        return new StudyGroupView(group.id(), group.name(), new FacultyView(group.facultyId(), group.facultyCode(), group.facultyName()));
    }
}

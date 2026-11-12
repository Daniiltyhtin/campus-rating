package ru.campusrating.activity.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.activity.ActivityCreated;
import ru.campusrating.activity.api.ActivityController.Activity;
import ru.campusrating.activity.api.ActivityController.MyAward;
import ru.campusrating.activity.api.ActivityController.UserRef;
import ru.campusrating.activity.domain.ActivityType;
import ru.campusrating.activity.domain.ActivityWindow;
import ru.campusrating.activity.internal.ActivityRepository.ActivityRow;
import ru.campusrating.identity.AuthenticatedUser;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.GroupMembershipState;
import ru.campusrating.identity.GroupRef;
import ru.campusrating.identity.Role;
import ru.campusrating.identity.StudentMember;
import ru.campusrating.identity.TeacherScope;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.FieldError;
import ru.campusrating.shared.error.ProblemCode;
import ru.campusrating.shared.error.ValidationFailedException;

@Service
public class ActivityService {
    private final ActivityRepository repository;
    private final CurrentUser current;
    private final TeacherScope scope;
    private final GroupMembers members;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;

    public ActivityService(ActivityRepository repository, CurrentUser current, TeacherScope scope, GroupMembers members, Clock clock,
                           ApplicationEventPublisher publisher) {
        this.repository = repository;
        this.current = current;
        this.scope = scope;
        this.members = members;
        this.clock = clock;
        this.publisher = publisher;
    }

    @Transactional
    public Activity create(String title, String description, ActivityType type, List<Long> groups, Instant startsAt, int maxPoints) {
        var user = current.require();
        Instant now = clock.instant();
        if (!startsAt.isAfter(now)) {
            throw invalid("startsAt", "Время начала должно быть в будущем");
        }
        if (new HashSet<>(groups).size() != groups.size()) {
            throw invalid("groupIds", "Группы не должны повторяться");
        }
        scope.requireAccessible(user.id(), groups);
        long id = repository.insert(user.id(), title, description, type, startsAt, maxPoints, now);
        repository.addGroups(id, groups);
        var students = members.activeStudents(groups).stream()
                .filter(member -> member.state() == GroupMembershipState.ACTIVE && groups.contains(member.groupId()))
                .map(StudentMember::studentId).distinct().toList();
        publisher.publishEvent(new ActivityCreated(id, title, students, now));
        return view(requireActivity(id, user), user, now);
    }

    @Transactional(readOnly = true)
    public List<Activity> list() {
        var user = current.require();
        Instant now = clock.instant();
        Long group = user.membership() == null ? null : user.membership().groupId();
        return views(repository.list(user.id(), user.role() == Role.TEACHER, group, now), user, now);
    }

    @Transactional(readOnly = true)
    public Activity get(long id) {
        var user = current.require();
        return view(requireActivity(id, user), user, clock.instant());
    }

    @Transactional
    public Activity enrollment(long id, boolean enroll) {
        var user = current.require();
        repository.lock(id);
        var activity = requireActivity(id, user);
        Instant now = clock.instant();
        if (activity.type() != ActivityType.SOCIAL) {
            throw new ApiException(ProblemCode.INVALID_STATE, "Запись только на общественные активности");
        }
        if (!ActivityWindow.at(activity.type(), activity.startsAt(), now).enrollmentOpen()) {
            throw new ApiException(ProblemCode.ENROLLMENT_CLOSED, "Запись закрыта");
        }
        if (enroll) {
            requireActiveMembership(user);
            repository.enroll(id, user.id(), now);
        } else {
            repository.cancel(id, user.id());
        }
        return view(requireActivity(id, user), user, now);
    }

    private void requireActiveMembership(AuthenticatedUser user) {
        var membership = members.membership(user.id());
        if (membership.isEmpty() || membership.get().state() != GroupMembershipState.ACTIVE) {
            throw new ApiException(ProblemCode.MEMBERSHIP_NOT_ACTIVE, "Членство в группе не активно");
        }
    }

    private ActivityRow requireActivity(long id, AuthenticatedUser user) {
        var activity = repository.find(id, user.id())
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Активность не найдена"));
        boolean allowed = user.role() == Role.TEACHER ? activity.authorId() == user.id() : belongs(id, user);
        if (!allowed) {
            throw new ApiException(ProblemCode.FORBIDDEN, "Активность недоступна");
        }
        return activity;
    }

    private boolean belongs(long id, AuthenticatedUser user) {
        return user.role() == Role.STUDENT
                && user.membership() != null
                && repository.groups(List.of(id)).get(id).contains(user.membership().groupId());
    }

    private Activity view(ActivityRow row, AuthenticatedUser user, Instant now) {
        return views(List.of(row), user, now).getFirst();
    }

    private List<Activity> views(List<ActivityRow> rows, AuthenticatedUser user, Instant now) {
        var links = repository.groups(rows.stream().map(ActivityRow::id).toList());
        var groups = members.groups(links.values().stream().flatMap(Collection::stream).distinct().toList());
        var authors = members.users(rows.stream().map(ActivityRow::authorId).distinct().toList());
        return rows.stream()
                .map(row -> view(row, links.get(row.id()), groups, authors.get(row.authorId()).fullName(), user, now))
                .toList();
    }

    private Activity view(ActivityRow row, List<Long> groupIds, Map<Long, GroupRef> groups, String author,
                          AuthenticatedUser user, Instant now) {
        var window = ActivityWindow.at(row.type(), row.startsAt(), now);
        var refs = groupIds.stream().map(groups::get).map(ActivityViews::group).toList();
        return new Activity(
                row.id(),
                row.title(),
                row.description(),
                row.type(),
                row.type().currency(),
                row.startsAt(),
                row.maxPoints(),
                refs,
                new UserRef(row.authorId(), author),
                window.enrollmentOpen(),
                window.confirmationOpen(),
                row.enrolledCount(),
                row.confirmedCount(),
                enrolled(row, user),
                award(row, user));
    }

    private @Nullable Boolean enrolled(ActivityRow row, AuthenticatedUser user) {
        if (user.role() == Role.STUDENT && row.type() == ActivityType.SOCIAL) {
            return row.enrolled();
        }
        return null;
    }

    private @Nullable MyAward award(ActivityRow row, AuthenticatedUser user) {
        if (user.role() == Role.STUDENT && row.awardLevel() != null) {
            return new MyAward(row.awardLevel(), row.awardAmount());
        }
        return null;
    }

    private ValidationFailedException invalid(String field, String message) {
        return new ValidationFailedException("Проверьте поля формы", List.of(new FieldError(field, "INVALID", message)));
    }
}

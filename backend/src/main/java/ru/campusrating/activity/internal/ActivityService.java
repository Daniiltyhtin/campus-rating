package ru.campusrating.activity.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.activity.domain.ActivityType;
import ru.campusrating.activity.domain.ActivityWindow;
import ru.campusrating.identity.AuthenticatedUser;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.GroupMembershipState;
import ru.campusrating.identity.Role;
import ru.campusrating.identity.TeacherScope;
import ru.campusrating.activity.api.ActivityController.Activity;
import ru.campusrating.activity.api.ActivityController.FacultyRef;
import ru.campusrating.activity.api.ActivityController.MyAward;
import ru.campusrating.activity.api.ActivityController.UserRef;
import ru.campusrating.activity.api.ActivityController.StudyGroupRef;
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
    public ActivityService(ActivityRepository repository, CurrentUser current, TeacherScope scope, GroupMembers members, Clock clock) {
        this.repository = repository; this.current = current; this.scope = scope; this.members = members; this.clock = clock;
    }

    @Transactional
    public Activity create(String title, String description, ActivityType type, List<Long> groups, Instant startsAt, int maxPoints) {
        var user = current.require();
        Instant now = clock.instant();
        if (!startsAt.isAfter(now)) invalid("startsAt", "Время начала должно быть в будущем");
        if (new HashSet<>(groups).size() != groups.size()) invalid("groupIds", "Группы не должны повторяться");
        scope.requireAccessible(user.id(), groups);
        long id = repository.insert(user.id(), title, description, type, startsAt, maxPoints, now);
        repository.addGroups(id, groups);
        return views(List.of(requireActivity(id, user)), user, now).getFirst();
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
        return views(List.of(requireActivity(id, user)), user, clock.instant()).getFirst();
    }
    @Transactional
    public Activity enrollment(long id, boolean enroll) {
        var user = current.require();
        var activity = requireActivity(id, user);
        Instant now = clock.instant();
        if (activity.type() != ActivityType.SOCIAL) throw new ApiException(ProblemCode.INVALID_STATE, "Запись только на общественные активности");
        if (!ActivityWindow.at(activity.type(), activity.startsAt(), now).enrollmentOpen()) {
            throw new ApiException(ProblemCode.ENROLLMENT_CLOSED, "Запись закрыта");
        }
        if (enroll) {
            var membership = members.membership(user.id());
            if (membership.isEmpty() || membership.get().state() != GroupMembershipState.ACTIVE) {
                throw new ApiException(ProblemCode.MEMBERSHIP_NOT_ACTIVE, "Членство в группе не активно");
            }
            repository.enroll(id, user.id(), now);
        } else repository.cancel(id, user.id());
        return views(List.of(requireActivity(id, user)), user, now).getFirst();
    }
    private ActivityRepository.ActivityRow requireActivity(long id, AuthenticatedUser user) {
        var activity = repository.find(id, user.id()).orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Активность не найдена"));
        boolean allowed = user.role() == Role.TEACHER ? activity.authorId() == user.id() : belongs(id, user);
        if (!allowed) throw new ApiException(ProblemCode.FORBIDDEN, "Активность недоступна");
        return activity;
    }
    private boolean belongs(long id, AuthenticatedUser user) {
        return user.role() == Role.STUDENT && user.membership() != null
                && repository.groups(List.of(id)).get(id).contains(user.membership().groupId());
    }
    private List<Activity> views(List<ActivityRepository.ActivityRow> rows, AuthenticatedUser user, Instant now) {
        var links = repository.groups(rows.stream().map(ActivityRepository.ActivityRow::id).toList());
        var groups = members.groups(links.values().stream().flatMap(Collection::stream).distinct().toList());
        var authors = members.users(rows.stream().map(ActivityRepository.ActivityRow::authorId).distinct().toList());
        return rows.stream().map(row -> {
            var window = ActivityWindow.at(row.type(), row.startsAt(), now);
            var refs = links.get(row.id()).stream().map(groups::get).map(group -> new StudyGroupRef(group.id(), group.name(),
                    new FacultyRef(group.facultyId(), group.facultyCode(), group.facultyName()))).toList();
            return new Activity(row.id(), row.title(), row.description(), row.type(), row.type().currency(), row.startsAt(), row.maxPoints(),
                    refs, new UserRef(row.authorId(), authors.get(row.authorId()).fullName()), window.enrollmentOpen(), window.confirmationOpen(), row.enrolledCount(), row.confirmedCount(),
                    enrolled(row, user), award(row, user));
        }).toList();
    }
    private @Nullable Boolean enrolled(ActivityRepository.ActivityRow row, AuthenticatedUser user) {
        return user.role() == Role.STUDENT && row.type() == ActivityType.SOCIAL ? row.enrolled() : null;
    }
    private @Nullable MyAward award(ActivityRepository.ActivityRow row, AuthenticatedUser user) {
        return user.role() == Role.STUDENT && row.awardLevel() != null ? new MyAward(row.awardLevel(), row.awardAmount()) : null;
    }
    private void invalid(String field, String message) {
        throw new ValidationFailedException("Проверьте поля формы", List.of(new FieldError(field, "INVALID", message)));
    }
}

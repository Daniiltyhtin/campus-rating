package ru.campusrating.identity.internal;

import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import ru.campusrating.directory.DirectoryApi;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.GroupRef;
import ru.campusrating.identity.Role;
import ru.campusrating.identity.StudentMember;
import ru.campusrating.identity.TeacherScope;
import ru.campusrating.identity.UserCard;
import ru.campusrating.identity.UserRef;
import ru.campusrating.identity.domain.TeacherAccess;
import ru.campusrating.shared.domain.MoscowTime;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

@Service
class IdentityReads implements GroupMembers, TeacherScope {
    private final IdentityRepository repository;
    private final DirectoryApi directory;
    private final Clock clock;
    IdentityReads(IdentityRepository repository, DirectoryApi directory, Clock clock) {
        this.repository = repository; this.directory = directory; this.clock = clock;
    }
    @Override public Set<Long> accessibleGroupIds(long teacherId) {
        var user = repository.byId(teacherId);
        if (user.isEmpty() || user.get().role() != Role.TEACHER || !user.get().approved()) return Set.of();
        var department = directory.department(user.get().departmentId()).orElseThrow();
        return TeacherAccess.groups(true, department.faculty().id(), directory.allGroups(), repository.grants(teacherId),
                MoscowTime.date(clock.instant()));
    }
    @Override public boolean isAccessible(long teacherId, long groupId) { return accessibleGroupIds(teacherId).contains(groupId); }
    @Override public void requireAccessible(long teacherId, Collection<Long> groupIds) {
        var available = accessibleGroupIds(teacherId);
        for (long group : groupIds) {
            if (!available.contains(group)) {
                String name = directory.group(group).map(info -> info.name()).orElse(Long.toString(group));
                throw new ApiException(ProblemCode.GROUP_NOT_ACCESSIBLE, "Группа " + name + " вне доступных групп");
            }
        }
    }
    @Override public List<StudentMember> activeStudents(Collection<Long> groupIds) { return repository.activeMembers(groupIds); }
    @Override public Optional<StudentMember> membership(long studentId) { return repository.memberships(List.of(studentId)).stream().findFirst(); }
    @Override public Map<Long, StudentMember> memberships(Collection<Long> ids) {
        return repository.memberships(ids).stream().collect(Collectors.toUnmodifiableMap(StudentMember::studentId, Function.identity()));
    }
    @Override public Map<Long, UserRef> users(Collection<Long> ids) {
        return repository.users(ids).stream().collect(Collectors.toUnmodifiableMap(UserAccount::id, user -> new UserRef(user.id(), user.fullName())));
    }
    @Override public Map<Long, GroupRef> groups(Collection<Long> ids) {
        return directory.groups(ids).values().stream().collect(Collectors.toUnmodifiableMap(info -> info.id(), info ->
                new GroupRef(info.id(), info.name(), info.faculty().id(), info.faculty().code(), info.faculty().name())));
    }
    @Override public Optional<UserCard> card(long id) {
        return repository.byId(id).map(user -> new UserCard(id, user.role(), user.fullName(),
                membership(id).map(StudentMember::groupId).orElse(null), user.departmentId()));
    }
}

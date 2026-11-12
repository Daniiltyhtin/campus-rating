package ru.campusrating.identity.internal;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.campusrating.directory.DirectoryApi;
import ru.campusrating.directory.GroupInfo;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.Role;
import ru.campusrating.identity.TeacherScope;
import ru.campusrating.identity.api.AdminTeachersController.AdminTeacher;
import ru.campusrating.identity.api.AdminTeachersController.AdminTeacherGrant;
import ru.campusrating.identity.api.AdminTeachersController.CourseGrant;
import ru.campusrating.identity.api.AdminTeachersController.FacultyCourseGrant;
import ru.campusrating.identity.api.AdminTeachersController.FacultyGrant;
import ru.campusrating.identity.api.AdminTeachersController.GroupGrant;
import ru.campusrating.identity.api.AuthDtos.StudyGroupRef;
import ru.campusrating.identity.domain.TeacherGrant;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

@Service
public class AdminTeacherService {
    private final AdminTeacherRepository repository;
    private final IdentityRepository users;
    private final DirectoryApi directory;
    private final TeacherScope scope;
    private final SessionRegistry sessions;
    private final CurrentUser current;
    private final Clock clock;

    AdminTeacherService(AdminTeacherRepository repository, IdentityRepository users, DirectoryApi directory,
                        TeacherScope scope, SessionRegistry sessions, CurrentUser current, Clock clock) {
        this.repository = repository; this.users = users; this.directory = directory; this.scope = scope;
        this.sessions = sessions; this.current = current; this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<AdminTeacher> list() { return repository.teachers().stream().map(this::get).toList(); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminTeacher get(long id) {
        var user = teacher(id);
        return new AdminTeacher(id, user.fullName(), user.email(), directory.department(user.departmentId()).orElseThrow(), user.approved());
    }

    @Transactional
    public AdminTeacher approve(long id, long department) {
        lock(id);
        if (directory.department(department).isEmpty()) throw Passwords.invalid("departmentId", "INVALID", "Кафедра не найдена");
        var user = teacher(id);
        if (user.approved()) {
            if (user.departmentId() != department) throw invalidState();
        } else {
            try {
                repository.approve(id, department, current.require().id(), clock.instant());
            } catch (DataIntegrityViolationException exception) {
                throw referenceFailure(exception, "departmentId");
            }
        }
        return get(id);
    }

    @Transactional
    public void reject(long id) {
        lock(id);
        if (teacher(id).approved()) throw invalidState();
        repository.reject(id);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { expireSessions(id); }
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<AdminTeacherGrant> grants(long id) {
        teacher(id);
        return repository.grants(id).stream().map(this::view).toList();
    }

    @Transactional
    public CreatedGrant createGrant(long id, TeacherGrant grant) {
        lock(id);
        var row = AdminTeacherRepository.GrantRow.from(grant);
        validateTarget(row);
        var existing = repository.identical(id, row);
        if (existing.isPresent()) return new CreatedGrant(view(existing.get()), false);
        try {
            return new CreatedGrant(view(repository.insertGrant(id, row)), true);
        } catch (DataIntegrityViolationException exception) {
            throw referenceFailure(exception, row.groupId() == null ? "facultyId" : "groupId");
        }
    }

    @Transactional
    public void deleteGrant(long id, long grant) {
        lock(id);
        if (!repository.deleteGrant(id, grant)) throw notFound();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<StudyGroupRef> accessibleGroups(long id) {
        teacher(id);
        return directory.groups(scope.accessibleGroupIds(id)).values().stream()
                .sorted(Comparator.comparing(GroupInfo::name).thenComparingLong(GroupInfo::id))
                .map(group -> new StudyGroupRef(group.id(), group.name(), group.faculty())).toList();
    }

    private void validateTarget(AdminTeacherRepository.GrantRow row) {
        if (row.groupId() != null && directory.group(row.groupId()).isEmpty())
            throw Passwords.invalid("groupId", "INVALID", "Группа не найдена");
        if (row.facultyId() != null && directory.faculty(row.facultyId()).isEmpty())
            throw Passwords.invalid("facultyId", "INVALID", "Факультет не найден");
    }

    private AdminTeacherGrant view(AdminTeacherRepository.GrantRow row) {
        return switch (row.kind()) {
            case "GROUP" -> {
                var group = directory.group(row.groupId()).orElseThrow();
                yield new GroupGrant(row.id(), new StudyGroupRef(group.id(), group.name(), group.faculty()));
            }
            case "FACULTY" -> new FacultyGrant(row.id(), directory.faculty(row.facultyId()).orElseThrow());
            case "FACULTY_COURSE" -> new FacultyCourseGrant(row.id(), directory.faculty(row.facultyId()).orElseThrow(), row.course());
            case "COURSE" -> new CourseGrant(row.id(), row.course());
            default -> throw new IllegalStateException("Unknown teacher grant kind");
        };
    }

    private RuntimeException referenceFailure(DataIntegrityViolationException exception, String field) {
        if (exception.getMostSpecificCause() instanceof java.sql.SQLException sql && "23503".equals(sql.getSQLState()))
            return Passwords.invalid(field, "INVALID", "Запись справочника не найдена");
        return exception;
    }

    private UserAccount teacher(long id) {
        return users.byId(id).filter(user -> user.role() == Role.TEACHER).orElseThrow(this::notFound);
    }
    private void lock(long id) { if (!repository.lockTeacher(id)) throw notFound(); }
    private ApiException notFound() { return new ApiException(ProblemCode.NOT_FOUND, "Преподаватель или выдача не найдены"); }
    private ApiException invalidState() { return new ApiException(ProblemCode.INVALID_STATE, "Преподаватель уже допущен"); }
    private void expireSessions(long id) {
        for (var principal : sessions.getAllPrincipals()) {
            if (principal instanceof IdentityPrincipal identity && identity.userId() == id)
                sessions.getAllSessions(principal, false).forEach(session -> session.expireNow());
        }
    }
    public record CreatedGrant(AdminTeacherGrant grant, boolean created) {}
}

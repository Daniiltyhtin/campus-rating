package ru.campusrating.profile.internal;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.checkpoint.StudentAssignmentView;
import ru.campusrating.checkpoint.StudentAssignments;
import ru.campusrating.directory.DirectoryApi;
import ru.campusrating.directory.FacultyInfo;
import ru.campusrating.identity.AuthenticatedUser;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.UserCard;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.profile.api.ProfileViews.AdminProfile;
import ru.campusrating.profile.api.ProfileViews.AdminProfileTask;
import ru.campusrating.profile.api.ProfileViews.OwnProfileTask;
import ru.campusrating.profile.api.ProfileViews.ProfileBalances;
import ru.campusrating.profile.api.ProfileViews.ProfileStudyGroup;
import ru.campusrating.profile.api.ProfileViews.ProfileTask;
import ru.campusrating.profile.api.ProfileViews.PublicProfileTask;
import ru.campusrating.profile.api.ProfileViews.StudentProfile;
import ru.campusrating.profile.api.ProfileViews.TeacherProfile;
import ru.campusrating.profile.api.ProfileViews.UserProfile;
import ru.campusrating.profile.domain.TaskVisibility;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

/** Профиль собирается из швов, своих таблиц у модуля нет (KTD6 плана U9–U11). */
@Service
public class ProfileService {
    private final CurrentUser current;
    private final GroupMembers members;
    private final DirectoryApi directory;
    private final LedgerApi ledger;
    private final StudentAssignments assignments;

    ProfileService(CurrentUser current, GroupMembers members, DirectoryApi directory, LedgerApi ledger,
                   StudentAssignments assignments) {
        this.current = current;
        this.members = members;
        this.directory = directory;
        this.ledger = ledger;
        this.assignments = assignments;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserProfile profile(long userId) {
        var viewer = current.require();
        var card = members.card(userId)
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Пользователь не найден"));
        return switch (card.role()) {
            case STUDENT -> student(card, viewer);
            case TEACHER -> teacher(card);
            case ADMIN -> new AdminProfile(card.id(), card.fullName());
        };
    }

    private StudentProfile student(UserCard card, AuthenticatedUser viewer) {
        if (card.groupId() == null) throw new IllegalStateException("Student " + card.id() + " has no group");
        var ref = members.groups(List.of(card.groupId())).get(card.groupId());
        if (ref == null) throw new IllegalStateException("Group " + card.groupId() + " not found");
        var group = new ProfileStudyGroup(ref.id(), ref.name(),
                new FacultyInfo(ref.facultyId(), ref.facultyCode(), ref.facultyName()));
        var balances = ledger.balances(card.id());
        var visibility = TaskVisibility.of(viewer.id(), viewer.role(), card.id());
        return new StudentProfile(card.id(), card.fullName(), group,
                new ProfileBalances(balances.ar(), balances.bua(), balances.boa()),
                tasks(assignments.active(card.id()), visibility),
                tasks(assignments.completed(card.id()), visibility));
    }

    private TeacherProfile teacher(UserCard card) {
        if (card.departmentId() == null) throw new IllegalStateException("Teacher " + card.id() + " has no department");
        var department = directory.department(card.departmentId())
                .orElseThrow(() -> new IllegalStateException("Department " + card.departmentId() + " not found"));
        return new TeacherProfile(card.id(), card.fullName(), department);
    }

    private static List<ProfileTask> tasks(List<StudentAssignmentView> views, TaskVisibility visibility) {
        return views.stream().map(view -> task(view, visibility)).toList();
    }

    private static ProfileTask task(StudentAssignmentView view, TaskVisibility visibility) {
        return switch (visibility) {
            case PUBLIC -> new PublicProfileTask(view.title(), view.deadline(), view.status(), view.charged());
            case OWN -> new OwnProfileTask(view.assignmentId(), view.title(), view.deadline(), view.status(), view.charged());
            case ADMIN -> new AdminProfileTask(view.assignmentId(), view.title(), view.deadline(), view.status(), view.charged());
        };
    }
}

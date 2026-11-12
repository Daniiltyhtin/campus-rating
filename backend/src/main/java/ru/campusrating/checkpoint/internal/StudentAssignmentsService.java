package ru.campusrating.checkpoint.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.checkpoint.StudentAssignmentView;
import ru.campusrating.checkpoint.StudentAssignments;
import ru.campusrating.checkpoint.api.StudentTaskPage;
import ru.campusrating.checkpoint.domain.AssignmentCursor;
import ru.campusrating.checkpoint.domain.AssignmentStatusPolicy;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.GroupMembershipState;
import ru.campusrating.identity.Role;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

@Service
@Transactional(readOnly = true)
public class StudentAssignmentsService implements StudentAssignments {
    private final AssignmentReadRepository repository;
    private final AssignmentReader reader;
    private final GroupMembers members;
    private final CurrentUser current;
    private final Clock clock;

    StudentAssignmentsService(
            AssignmentReadRepository repository,
            AssignmentReader reader,
            GroupMembers members,
            CurrentUser current,
            Clock clock) {
        this.repository = repository;
        this.reader = reader;
        this.members = members;
        this.current = current;
        this.clock = clock;
    }

    public StudentTaskPage mine(String view, String cursorValue, String limitValue) {
        if (!"active".equals(view) && !"completed".equals(view)) {
            throw CheckpointValidation.invalid("view", "Укажите active или completed");
        }
        var user = current.require();
        if (user.role() != Role.STUDENT) {
            throw new ApiException(ProblemCode.FORBIDDEN, "Задачи доступны студенту");
        }
        var now = clock.instant();
        if (view.equals("active")) {
            return activePage(user.id(), now);
        }
        return completedPage(user.id(), cursorValue, limitValue, now);
    }

    private StudentTaskPage activePage(long studentId, Instant now) {
        if (!isActive(studentId)) {
            return pending();
        }
        return new StudentTaskPage(
                reader.tasks(repository.active(studentId, now), now),
                null,
                GroupMembershipState.ACTIVE);
    }

    private StudentTaskPage completedPage(
            long studentId, String cursorValue, String limitValue, Instant now) {
        String filter = "completed/" + studentId;
        var cursor = AssignmentReadValidation.cursor(cursorValue, filter);
        int limit = AssignmentReadValidation.limit(limitValue);
        if (!isActive(studentId)) {
            return pending();
        }
        var rows = repository.completed(studentId, cursor, limit + 1);
        var page = rows.subList(0, Math.min(rows.size(), limit));
        String next = null;
        if (rows.size() > limit) {
            var last = page.getLast().assignment();
            next = new AssignmentCursor(last.acceptedAt().toString(), last.id(), filter).encode();
        }
        return new StudentTaskPage(reader.tasks(page, now), next, GroupMembershipState.ACTIVE);
    }

    @Override
    public List<StudentAssignmentView> active(long studentId) {
        var now = clock.instant();
        return isActive(studentId) ? views(repository.active(studentId, now), now) : List.of();
    }

    @Override
    public List<StudentAssignmentView> completed(long studentId) {
        var now = clock.instant();
        return isActive(studentId)
                ? views(repository.completed(studentId, null, Integer.MAX_VALUE), now)
                : List.of();
    }

    private List<StudentAssignmentView> views(List<AssignmentReadData> rows, Instant now) {
        return rows.stream()
                .map(
                        row -> {
                            var assignment = row.assignment();
                            var checkpoint = row.checkpoint();
                            return new StudentAssignmentView(
                                    assignment.id(),
                                    checkpoint.title(),
                                    checkpoint.deadline(),
                                    AssignmentStatusPolicy.status(
                                            assignment.state(), checkpoint.deadline(), now),
                                    assignment.charged(),
                                    assignment.acceptedAt());
                        })
                .toList();
    }

    private boolean isActive(long studentId) {
        return members.membership(studentId)
                .map(member -> member.state() == GroupMembershipState.ACTIVE)
                .orElse(false);
    }

    private StudentTaskPage pending() {
        return new StudentTaskPage(List.of(), null, GroupMembershipState.PENDING);
    }
}

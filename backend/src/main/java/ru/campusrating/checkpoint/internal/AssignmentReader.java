package ru.campusrating.checkpoint.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import ru.campusrating.checkpoint.AssignmentState;
import ru.campusrating.checkpoint.api.AssignmentView.CheckpointSummary;
import ru.campusrating.checkpoint.api.AssignmentView.Penalty;
import ru.campusrating.checkpoint.api.AssignmentView;
import ru.campusrating.checkpoint.api.CheckpointAssignmentRow.SubmissionKind;
import ru.campusrating.checkpoint.api.CheckpointAssignmentRow;
import ru.campusrating.checkpoint.api.CheckpointView.FacultyView;
import ru.campusrating.checkpoint.api.CheckpointView.StudyGroupView;
import ru.campusrating.checkpoint.api.CheckpointView.UserView;
import ru.campusrating.checkpoint.api.StudentTask;
import ru.campusrating.checkpoint.domain.AssignmentStatusPolicy;
import ru.campusrating.checkpoint.domain.PenaltyView;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.GroupRef;
import ru.campusrating.ledger.LedgerApi;

@Component
class AssignmentReader {
    private final SubmissionRepository submissions;
    private final GroupMembers members;
    private final LedgerApi ledger;
    private final Clock clock;

    AssignmentReader(
            SubmissionRepository submissions, GroupMembers members, LedgerApi ledger, Clock clock) {
        this.submissions = submissions;
        this.members = members;
        this.ledger = ledger;
        this.clock = clock;
    }

    AssignmentView view(AssignmentData assignment, CheckpointData checkpoint) {
        var versions = submissions.versions(assignment.id());
        var latest = versions.isEmpty() ? null : versions.getFirst();
        var now = clock.instant();
        return new AssignmentView(
                assignment.id(),
                summary(checkpoint),
                new UserView(assignment.studentId(), assignment.studentFullName()),
                group(assignment.groupId()),
                assignment.state(),
                AssignmentStatusPolicy.status(assignment.state(), checkpoint.deadline(), now),
                penalty(assignment, checkpoint, ledger.balances(assignment.studentId()).ar(), now),
                returnComment(assignment, latest),
                latest == null ? null : latest.view(),
                versions.stream().map(SubmissionData::view).toList(),
                latest == null ? null : latest.submittedAt(),
                assignment.acceptedAt());
    }

    List<StudentTask> tasks(List<AssignmentReadData> rows, Instant now) {
        var balances =
                ledger.balances(
                        rows.stream().map(row -> row.assignment().studentId()).distinct().toList());
        var teachers =
                members.users(
                        rows.stream().map(row -> row.checkpoint().authorId()).distinct().toList());
        return rows.stream()
                .map(
                        row -> {
                            var assignment = row.assignment();
                            var checkpoint = row.checkpoint();
                            var teacher = teachers.get(checkpoint.authorId());
                            return new StudentTask(
                                    assignment.id(),
                                    new StudentTask.CheckpointTitle(
                                            checkpoint.id(), checkpoint.title()),
                                    new UserView(teacher.id(), teacher.fullName()),
                                    checkpoint.deadline(),
                                    checkpoint.cost(),
                                    assignment.state(),
                                    AssignmentStatusPolicy.status(
                                            assignment.state(), checkpoint.deadline(), now),
                                    penalty(
                                            assignment,
                                            checkpoint,
                                            balances.get(assignment.studentId()).ar(),
                                            now),
                                    assignment.acceptedAt());
                        })
                .toList();
    }

    List<CheckpointAssignmentRow> table(List<AssignmentReadData> rows, Instant now) {
        var groups =
                members.groups(
                        rows.stream().map(row -> row.assignment().groupId()).distinct().toList());
        return rows.stream()
                .map(
                        row -> {
                            var assignment = row.assignment();
                            return new CheckpointAssignmentRow(
                                    assignment.id(),
                                    new UserView(
                                            assignment.studentId(), assignment.studentFullName()),
                                    group(groups.get(assignment.groupId())),
                                    assignment.state(),
                                    AssignmentStatusPolicy.status(
                                            assignment.state(), row.checkpoint().deadline(), now),
                                    assignment.charged(),
                                    row.submittedAt(),
                                    row.submissionKind() == null
                                            ? null
                                            : SubmissionKind.valueOf(row.submissionKind()));
                        })
                .toList();
    }

    private Penalty penalty(
            AssignmentData assignment, CheckpointData checkpoint, int ar, Instant now) {
        var penalty =
                PenaltyView.calculate(
                        assignment.state(),
                        checkpoint.deadline(),
                        checkpoint.cost(),
                        checkpoint.penaltyStep(),
                        checkpoint.penaltyIntervalSeconds(),
                        assignment.charged(),
                        assignment.lastPenaltySlot(),
                        ar,
                        now);
        return new Penalty(
                penalty.charged(),
                penalty.cap(),
                penalty.step(),
                penalty.intervalSeconds(),
                penalty.phase(),
                penalty.nextAt(),
                penalty.nextAmount());
    }

    private String returnComment(AssignmentData assignment, SubmissionData latest) {
        if (assignment.state() == AssignmentState.OPEN && latest != null) {
            return latest.returnComment();
        }
        return null;
    }

    private CheckpointSummary summary(CheckpointData checkpoint) {
        var author = members.users(List.of(checkpoint.authorId())).get(checkpoint.authorId());
        return new CheckpointSummary(
                checkpoint.id(),
                checkpoint.title(),
                checkpoint.description(),
                checkpoint.deadline(),
                checkpoint.cost(),
                new UserView(author.id(), author.fullName()));
    }

    private StudyGroupView group(long id) {
        var group = members.groups(List.of(id)).get(id);
        return group(group);
    }

    private StudyGroupView group(GroupRef group) {
        return new StudyGroupView(
                group.id(),
                group.name(),
                new FacultyView(group.facultyId(), group.facultyCode(), group.facultyName()));
    }
}

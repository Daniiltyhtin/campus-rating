package ru.campusrating.checkpoint.internal;

import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;
import ru.campusrating.checkpoint.AssignmentState;
import ru.campusrating.checkpoint.api.AssignmentView.CheckpointSummary;
import ru.campusrating.checkpoint.api.AssignmentView.Penalty;
import ru.campusrating.checkpoint.api.AssignmentView;
import ru.campusrating.checkpoint.api.CheckpointView.FacultyView;
import ru.campusrating.checkpoint.api.CheckpointView.StudyGroupView;
import ru.campusrating.checkpoint.api.CheckpointView.UserView;
import ru.campusrating.checkpoint.domain.AssignmentStatusPolicy;
import ru.campusrating.checkpoint.domain.PenaltyView;
import ru.campusrating.identity.GroupMembers;
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
        var penalty =
                PenaltyView.calculate(
                        assignment.state(),
                        checkpoint.deadline(),
                        checkpoint.cost(),
                        checkpoint.penaltyStep(),
                        checkpoint.penaltyIntervalSeconds(),
                        assignment.charged(),
                        assignment.lastPenaltySlot(),
                        ledger.balances(assignment.studentId()).ar(),
                        now);
        return new AssignmentView(
                assignment.id(),
                summary(checkpoint),
                new UserView(assignment.studentId(), assignment.studentFullName()),
                group(assignment.groupId()),
                assignment.state(),
                AssignmentStatusPolicy.status(assignment.state(), checkpoint.deadline(), now),
                new Penalty(
                        penalty.charged(),
                        penalty.cap(),
                        penalty.step(),
                        penalty.intervalSeconds(),
                        penalty.phase(),
                        penalty.nextAt(),
                        penalty.nextAmount()),
                returnComment(assignment, latest),
                latest == null ? null : latest.view(),
                versions.stream().map(SubmissionData::view).toList(),
                latest == null ? null : latest.submittedAt(),
                assignment.acceptedAt());
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
        return new StudyGroupView(
                group.id(),
                group.name(),
                new FacultyView(group.facultyId(), group.facultyCode(), group.facultyName()));
    }
}

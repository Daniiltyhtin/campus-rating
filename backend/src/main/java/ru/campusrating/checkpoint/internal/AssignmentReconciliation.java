package ru.campusrating.checkpoint.internal;

import java.time.Clock;
import org.springframework.stereotype.Service;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.GroupMembershipState;
import ru.campusrating.identity.MembershipActivated;
import ru.campusrating.identity.StudentMember;

@Service
class AssignmentReconciliation {
    private final CheckpointRepository repository;
    private final GroupMembers members;
    private final Clock clock;

    AssignmentReconciliation(CheckpointRepository repository, GroupMembers members, Clock clock) {
        this.repository = repository;
        this.members = members;
        this.clock = clock;
    }

    void checkpoint(long checkpointId) {
        repository.find(checkpointId).ifPresent(checkpoint -> {
            var groups = repository.groups(checkpointId);
            members.activeStudents(groups).stream().filter(member -> groups.contains(member.groupId()))
                    .forEach(member -> assign(checkpoint, member));
        });
    }

    void activation(MembershipActivated event) {
        members.membership(event.studentId()).filter(member -> member.groupId() == event.groupId())
                .ifPresent(member -> activate(event, member));
    }

    private void activate(MembershipActivated event, StudentMember member) {
        if (member.state() != GroupMembershipState.ACTIVE) return;
        repository.forActivation(event.groupId(), event.activatedAt())
                .forEach(checkpoint -> repository.assign(checkpoint, member, event.activatedAt(), clock.instant()));
    }

    private void assign(CheckpointData checkpoint, StudentMember member) {
        if (member.state() != GroupMembershipState.ACTIVE || member.activatedAt() == null) return;
        repository.assign(checkpoint, member, member.activatedAt(), clock.instant());
    }
}

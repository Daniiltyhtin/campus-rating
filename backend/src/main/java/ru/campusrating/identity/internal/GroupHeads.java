package ru.campusrating.identity.internal;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.directory.DirectoryApi;
import ru.campusrating.identity.GroupMembershipState;
import ru.campusrating.identity.UserRef;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

@Service
public class GroupHeads {
    public record Assigned(long groupId, UserRef student) {}

    private final MembershipRepository repository;
    private final DirectoryApi directory;
    private final Clock clock;
    GroupHeads(MembershipRepository repository, DirectoryApi directory, Clock clock) {
        this.repository = repository; this.directory = directory; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Assigned> list() {
        return repository.heads().stream().map(head -> new Assigned(head.groupId(), new UserRef(head.studentId(), head.fullName()))).toList();
    }

    @Transactional(readOnly = true)
    public boolean isHead(long studentId) {
        return repository.headedGroup(studentId).isPresent();
    }

    @Transactional
    public Assigned assign(long groupId, long studentId) {
        if (directory.group(groupId).isEmpty()) throw new ApiException(ProblemCode.NOT_FOUND, "Группа не найдена");
        var membership = repository.lock(studentId)
                .filter(member -> member.groupId() == groupId && member.state() == GroupMembershipState.ACTIVE)
                .orElseThrow(() -> Passwords.invalid("studentId", "INVALID", "Старостой может быть только студент группы с подтверждённым членством"));
        repository.assign(groupId, membership.studentId(), clock.instant().truncatedTo(ChronoUnit.MICROS));
        return repository.heads().stream().filter(head -> head.groupId() == groupId)
                .map(head -> new Assigned(groupId, new UserRef(head.studentId(), head.fullName()))).findFirst().orElseThrow();
    }

    @Transactional
    public void remove(long groupId) {
        if (!repository.remove(groupId)) throw new ApiException(ProblemCode.NOT_FOUND, "У группы нет старосты");
    }
}

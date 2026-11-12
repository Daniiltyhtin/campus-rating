package ru.campusrating.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.directory.DirectoryApi;
import ru.campusrating.directory.GroupInfo;
import ru.campusrating.identity.AuthenticatedUser;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.MembershipActivated;
import ru.campusrating.identity.Role;
import ru.campusrating.identity.TeacherScope;
import ru.campusrating.identity.domain.MembershipTransition;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

@Service
public class MembershipApproval {
    public record PendingRequest(long studentId, String fullName, String email, GroupInfo group, Instant requestedAt) {}

    private final MembershipRepository repository;
    private final CurrentUser current;
    private final TeacherScope scope;
    private final DirectoryApi directory;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    MembershipApproval(MembershipRepository repository, CurrentUser current, TeacherScope scope, DirectoryApi directory,
                       ApplicationEventPublisher events, Clock clock) {
        this.repository = repository; this.current = current; this.scope = scope; this.directory = directory;
        this.events = events; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PendingRequest> pending() {
        var rows = repository.pending(decidableGroups(current.require()));
        var groups = directory.groups(rows.stream().map(MembershipRepository.Pending::groupId).toList());
        return rows.stream().map(row -> new PendingRequest(row.studentId(), row.fullName(), row.email(),
                groups.get(row.groupId()), row.requestedAt())).toList();
    }

    @Transactional
    public void approve(long studentId) {
        var membership = decidable(studentId);
        switch (MembershipTransition.approve(membership.state())) {
            case APPLY -> {
                var time = now();
                repository.activate(studentId, time);
                events.publishEvent(new MembershipActivated(studentId, membership.groupId(), time));
            }
            case UNCHANGED -> { }
            case CONFLICT -> throw new ApiException(ProblemCode.INVALID_STATE, "Заявка уже отклонена");
        }
    }

    @Transactional
    public void reject(long studentId) {
        var membership = decidable(studentId);
        switch (MembershipTransition.reject(membership.state())) {
            case APPLY -> repository.reject(studentId);
            case UNCHANGED -> { }
            case CONFLICT -> throw new ApiException(ProblemCode.INVALID_STATE, "Членство уже подтверждено");
        }
    }

    @Transactional
    public void reapply(long facultyId, long groupId) {
        var actor = current.require();
        if (actor.role() != Role.STUDENT) throw forbidden();
        var membership = repository.lock(actor.id()).orElseThrow(this::notFound);
        if (MembershipTransition.reapply(membership.state()) != MembershipTransition.APPLY) {
            throw new ApiException(ProblemCode.INVALID_STATE, "Заявку можно подать заново только после отклонения");
        }
        var group = directory.group(groupId).orElseThrow(() -> Passwords.invalid("groupId", "INVALID", "Группа не найдена"));
        if (group.faculty().id() != facultyId) throw Passwords.invalid("groupId", "MISMATCH", "Группа не принадлежит факультету");
        repository.reapply(actor.id(), groupId, now());
    }

    private MembershipRepository.Locked decidable(long studentId) {
        var actor = current.require();
        if (actor.role() != Role.TEACHER && actor.role() != Role.STUDENT) throw forbidden();
        var membership = repository.lock(studentId).orElseThrow(this::notFound);
        if (!decidableGroups(actor).contains(membership.groupId())) throw forbidden();
        return membership;
    }

    private Set<Long> decidableGroups(AuthenticatedUser actor) {
        return switch (actor.role()) {
            case TEACHER -> scope.accessibleGroupIds(actor.id());
            case STUDENT -> repository.headedGroup(actor.id()).map(Set::of).orElse(Set.of());
            case ADMIN -> Set.of();
        };
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private ApiException forbidden() { return new ApiException(ProblemCode.FORBIDDEN, "Нет доступа"); }
    private ApiException notFound() { return new ApiException(ProblemCode.NOT_FOUND, "Заявка не найдена"); }
}

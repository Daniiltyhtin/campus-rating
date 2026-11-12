package ru.campusrating.identity.internal;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.identity.GroupMembershipState;
import ru.campusrating.identity.MembershipActivated;
import ru.campusrating.ledger.LedgerApi;

@Component
@Profile("demo")
class DemoIdentity implements ApplicationRunner {
    private final IdentityRepository repository;
    private final LedgerApi ledger;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final MembershipRepository heads;
    DemoIdentity(IdentityRepository repository, LedgerApi ledger, Clock clock, ApplicationEventPublisher events, MembershipRepository heads) {
        this.repository = repository; this.ledger = ledger; this.clock = clock; this.events = events; this.heads = heads;
    }
    @Override @Transactional public void run(ApplicationArguments arguments) {
        var time = clock.instant().truncatedTo(ChronoUnit.MICROS);
        for (long id : List.of(1001L, 1002L, 1003L)) {
            if (repository.addMembership(id, 11, GroupMembershipState.ACTIVE, time, time)) events.publishEvent(new MembershipActivated(id, 11, time));
        }
        repository.addMembership(1004, 11, GroupMembershipState.PENDING, null, time);
        heads.assignIfAbsent(11, 1001, time);
        ledger.post(List.of(RegistrationService.initial(1001), RegistrationService.initial(1002),
                RegistrationService.initial(1003), RegistrationService.initial(1004)));
    }
}

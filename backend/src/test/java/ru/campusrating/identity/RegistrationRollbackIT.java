package ru.campusrating.identity;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.AopTestUtils;
import ru.campusrating.ledger.LedgerApi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

@Import(MembershipEventProbe.class)
class RegistrationRollbackIT extends IdentityAcceptanceSupport {
    @MockitoSpyBean private LedgerApi failingLedger;
    @Autowired private MembershipEventProbe.Listener membershipEvents;

    @Test
    void postingFailureRollsBackUserAndMembership() {
        LedgerApi target = AopTestUtils.getUltimateTargetObject(failingLedger);
        doThrow(new IllegalStateException("Synthetic posting failure")).when(target).post(anyList());
        var response = browser().post("/api/v1/auth/register/student", studentBody(email("rollback"), faculty, group));
        assertProblem(response, 500, "INTERNAL_ERROR");
        assertThat(jdbc.queryForObject("select count(*) from app_user where email=?", Integer.class, email("rollback"))).isZero();
        assertThat(jdbc.queryForObject("select count(*) from group_membership where group_id=?", Integer.class, group)).isZero();
        assertThat(failingLedger.reconcile().mismatches()).isEmpty();
        assertThat(membershipEvents.committed()).noneMatch(event -> event.groupId() == group);
        var publications = jdbc.queryForList("select serialized_event from event_publication", String.class);
        assertThat(publications.stream().map(JSON::readTree).filter(event -> event.path("groupId").asLong() == group)).isEmpty();
    }
}

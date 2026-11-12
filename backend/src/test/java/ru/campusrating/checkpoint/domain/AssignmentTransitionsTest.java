package ru.campusrating.checkpoint.domain;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.campusrating.checkpoint.AssignmentState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssignmentTransitionsTest {
    @ParameterizedTest
    @CsvSource({
            "OPEN,SUBMIT,SUBMITTED", "SUBMITTED,ACCEPT,ACCEPTED", "SUBMITTED,RETURN_BACK,OPEN",
            "SUBMITTED,SUBMIT,", "ACCEPTED,SUBMIT,", "OPEN,ACCEPT,", "ACCEPTED,ACCEPT,",
            "OPEN,RETURN_BACK,", "ACCEPTED,RETURN_BACK,"
    })
    void fullTransitionMatrix(AssignmentState source, InvalidAssignmentStateException.Action action,
                              AssignmentState target) {
        if (target != null) {
            assertThat(transition(source, action)).isEqualTo(target);
        } else {
            assertThatThrownBy(() -> transition(source, action))
                    .isInstanceOfSatisfying(InvalidAssignmentStateException.class, error -> {
                        assertThat(error.code()).isEqualTo("INVALID_STATE");
                        assertThat(error.state()).isEqualTo(source);
                        assertThat(error.action()).isEqualTo(action);
                    });
        }
    }

    @Test
    void withdrawsStrictlyBeforeDeadline() {
        var deadline = Instant.parse("2026-11-12T11:00:00Z");
        assertThat(AssignmentTransitions.withdraw(
                AssignmentState.SUBMITTED, deadline, deadline.minusNanos(1)))
                .isEqualTo(AssignmentState.OPEN);
    }

    @ParameterizedTest
    @CsvSource({
        "SUBMITTED,0,DEADLINE_REACHED", "SUBMITTED,1,DEADLINE_REACHED",
        "OPEN,-1,STATE", "OPEN,0,STATE", "OPEN,1,STATE",
        "ACCEPTED,-1,STATE", "ACCEPTED,0,STATE", "ACCEPTED,1,STATE"
    })
    void rejectsWithdrawal(AssignmentState state, long offset,
                           InvalidAssignmentStateException.Reason reason) {
        var deadline = Instant.parse("2026-11-12T11:00:00Z");
        assertThatThrownBy(() -> AssignmentTransitions.withdraw(state, deadline, deadline.plusNanos(offset)))
                .isInstanceOfSatisfying(InvalidAssignmentStateException.class, error -> {
                    assertThat(error.code()).isEqualTo("INVALID_STATE");
                    assertThat(error.state()).isEqualTo(state);
                    assertThat(error.action()).isEqualTo(InvalidAssignmentStateException.Action.WITHDRAW);
                    assertThat(error.reason()).isEqualTo(reason);
                });
    }

    private AssignmentState transition(AssignmentState source, InvalidAssignmentStateException.Action action) {
        return switch (action) {
            case SUBMIT -> AssignmentTransitions.submit(source);
            case ACCEPT -> AssignmentTransitions.accept(source);
            case RETURN_BACK -> AssignmentTransitions.returnBack(source);
            case WITHDRAW -> throw new IllegalArgumentException("Withdrawal requires a deadline");
        };
    }
}

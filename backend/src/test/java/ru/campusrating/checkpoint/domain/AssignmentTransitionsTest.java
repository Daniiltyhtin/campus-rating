package ru.campusrating.checkpoint.domain;

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

    private AssignmentState transition(AssignmentState source, InvalidAssignmentStateException.Action action) {
        return switch (action) {
            case SUBMIT -> AssignmentTransitions.submit(source);
            case ACCEPT -> AssignmentTransitions.accept(source);
            case RETURN_BACK -> AssignmentTransitions.returnBack(source);
        };
    }
}

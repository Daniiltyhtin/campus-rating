package ru.campusrating.checkpoint.domain;

import java.util.Objects;
import ru.campusrating.checkpoint.AssignmentState;

import static ru.campusrating.checkpoint.AssignmentState.*;
import static ru.campusrating.checkpoint.domain.InvalidAssignmentStateException.Action.*;

public final class AssignmentTransitions {
    private AssignmentTransitions() {}

    public static AssignmentState submit(AssignmentState state) {
        return transition(state, OPEN, SUBMITTED, SUBMIT);
    }

    public static AssignmentState accept(AssignmentState state) {
        return transition(state, SUBMITTED, ACCEPTED, ACCEPT);
    }

    public static AssignmentState returnBack(AssignmentState state) {
        return transition(state, SUBMITTED, OPEN, RETURN_BACK);
    }

    private static AssignmentState transition(AssignmentState state, AssignmentState required,
                                               AssignmentState target, InvalidAssignmentStateException.Action action) {
        if (Objects.requireNonNull(state) != required) {
            throw new InvalidAssignmentStateException(state, action);
        }
        return target;
    }
}

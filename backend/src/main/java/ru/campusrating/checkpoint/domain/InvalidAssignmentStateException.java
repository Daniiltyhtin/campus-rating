package ru.campusrating.checkpoint.domain;

import ru.campusrating.checkpoint.AssignmentState;

public final class InvalidAssignmentStateException extends RuntimeException {
    public enum Action { SUBMIT, ACCEPT, RETURN_BACK }

    private final AssignmentState state;
    private final Action action;

    public InvalidAssignmentStateException(AssignmentState state, Action action) {
        super("Cannot " + action + " assignment in " + state);
        this.state = state;
        this.action = action;
    }

    public String code() {
        return "INVALID_STATE";
    }

    public AssignmentState state() {
        return state;
    }

    public Action action() {
        return action;
    }
}

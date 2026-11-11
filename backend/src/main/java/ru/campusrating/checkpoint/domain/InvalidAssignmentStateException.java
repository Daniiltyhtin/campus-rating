package ru.campusrating.checkpoint.domain;

import ru.campusrating.checkpoint.AssignmentState;

public final class InvalidAssignmentStateException extends RuntimeException {
    public enum Action { SUBMIT, ACCEPT, RETURN_BACK, WITHDRAW }

    public enum Reason { STATE, DEADLINE_REACHED }

    private final Reason reason;
    private final AssignmentState state;
    private final Action action;

    public InvalidAssignmentStateException(AssignmentState state, Action action) {
        this(state, action, Reason.STATE);
    }

    public InvalidAssignmentStateException(AssignmentState state, Action action, Reason reason) {
        super("Cannot " + action + " assignment in " + state + ": " + reason);
        this.reason = reason;
        this.state = state;
        this.action = action;
    }

    public Reason reason() {
        return reason;
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

package ru.campusrating.identity.domain;

import ru.campusrating.identity.GroupMembershipState;

public enum MembershipTransition {
    APPLY, UNCHANGED, CONFLICT;

    public static MembershipTransition approve(GroupMembershipState state) {
        return switch (state) {
            case PENDING -> APPLY;
            case ACTIVE -> UNCHANGED;
            case REJECTED -> CONFLICT;
        };
    }

    public static MembershipTransition reject(GroupMembershipState state) {
        return switch (state) {
            case PENDING -> APPLY;
            case REJECTED -> UNCHANGED;
            case ACTIVE -> CONFLICT;
        };
    }

    public static MembershipTransition reapply(GroupMembershipState state) {
        return state == GroupMembershipState.REJECTED ? APPLY : CONFLICT;
    }
}

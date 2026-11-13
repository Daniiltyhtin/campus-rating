package ru.campusrating.poll.domain;

import java.util.Set;

public final class Ballot {
    private final Set<Long> optionIds;

    Ballot(Set<Long> optionIds) {
        this.optionIds = Set.copyOf(optionIds);
    }

    public Set<Long> optionIds() {
        return optionIds;
    }
}

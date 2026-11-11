package ru.campusrating.activity.domain;

import java.util.List;

public final class BudgetPolicy {
    private BudgetPolicy() {}
    public record GroupBudget(long groupId, int needed, int spent) {}
    public record GroupCheck(long groupId, int needed, int remaining, int shortfall) {}
    public record BudgetCheck(List<GroupCheck> groups) {
        public BudgetCheck { groups = List.copyOf(groups); }
        public boolean withinBudget() { return groups.stream().allMatch(group -> group.shortfall() == 0); }
    }
    public static BudgetCheck check(List<GroupBudget> groups, int limit) {
        return new BudgetCheck(groups.stream().map(group -> {
            int remaining = Math.max(0, limit - group.spent());
            return new GroupCheck(group.groupId(), group.needed(), remaining, Math.max(0, group.needed() - remaining));
        }).toList());
    }
}

package ru.campusrating.activity.domain;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetPolicyTest {
    @Test
    void exactRemainingFitsAndOneMoreHasShortfall() {
        var exact = BudgetPolicy.check(List.of(new BudgetPolicy.GroupBudget(11, 40, 60)), 100);
        assertThat(exact.withinBudget()).isTrue();
        assertThat(exact.groups().getFirst().remaining()).isEqualTo(40);
        assertThat(exact.groups().getFirst().shortfall()).isZero();
        var over = BudgetPolicy.check(List.of(new BudgetPolicy.GroupBudget(11, 41, 60)), 100);
        assertThat(over.withinBudget()).isFalse();
        assertThat(over.groups().getFirst().shortfall()).isEqualTo(1);
    }

    @Test
    void onlyInsufficientGroupHasShortfall() {
        var result = BudgetPolicy.check(List.of(new BudgetPolicy.GroupBudget(11, 40, 60),
                new BudgetPolicy.GroupBudget(12, 41, 60)), 100);
        assertThat(result.withinBudget()).isFalse();
        assertThat(result.groups()).extracting(BudgetPolicy.GroupCheck::shortfall).containsExactly(0, 1);
    }

    @Test
    void loweredLimitClampsRemainingAtZero() {
        var result = BudgetPolicy.check(List.of(new BudgetPolicy.GroupBudget(11, 1, 101)), 100);
        assertThat(result.groups().getFirst().remaining()).isZero();
        assertThat(result.groups().getFirst().shortfall()).isEqualTo(1);
        assertThat(result.withinBudget()).isFalse();
        assertThat(BudgetPolicy.check(List.of(), 100).withinBudget()).isTrue();
        assertThat(BudgetPolicy.remaining(100, 30)).isEqualTo(70);
        assertThat(BudgetPolicy.remaining(100, 130)).isZero();
    }
}

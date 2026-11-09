package ru.campusrating.shared.error;

import java.util.List;

public final class BudgetExceededException extends ApiException {
    private final List<BudgetCheck> budgets;

    public BudgetExceededException(String detail, List<BudgetCheck> budgets) {
        super(ProblemCode.BUDGET_EXCEEDED, detail);
        this.budgets = List.copyOf(budgets);
    }

    public List<BudgetCheck> budgets() { return budgets; }
}

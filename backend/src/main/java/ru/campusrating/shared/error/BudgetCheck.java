package ru.campusrating.shared.error;

public record BudgetCheck(long groupId, String groupName, int needed, int remaining, int shortfall) {}

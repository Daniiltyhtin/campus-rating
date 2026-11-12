package ru.campusrating.activity;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

public interface BudgetStatistics {
    record Month(YearMonth month, long teacherId, int spent, int awards, int bua, int boa) {}
    List<Month> summarize(long groupId, YearMonth from, YearMonth to, Optional<Long> teacherId);
}

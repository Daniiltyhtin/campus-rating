package ru.campusrating.activity.internal;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import ru.campusrating.activity.BudgetStatistics;

@Service
public class BudgetStatisticsService implements BudgetStatistics {
    private final BudgetRepository repository;

    BudgetStatisticsService(BudgetRepository repository) { this.repository = repository; }

    @Override
    public List<Month> summarize(long groupId, YearMonth from, YearMonth to, Optional<Long> teacherId) {
        return repository.summarize(groupId, from, to, teacherId);
    }
}

package ru.campusrating.activity;

import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.campusrating.support.AnalyticsTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class BudgetStatisticsIT extends AnalyticsTestSupport {
    @Autowired BudgetStatistics statistics;

    @Test
    void ae6MonthlyStatisticsCountAwardsAndFilterTeacher() {
        long a = member("a", "Антонов Антон", "2026-11-01T00:00:00");
        novemberBudget(a);
        var from = YearMonth.of(2026, 6);
        var to = YearMonth.of(2026, 11);
        assertThat(statistics.summarize(group, from, to, Optional.of(petrov)))
                .extracting("month", "teacherId", "spent", "awards", "bua", "boa")
                .containsExactly(tuple(to, petrov, 950, 3, 800, 150));
        assertThat(statistics.summarize(group, from, to, Optional.empty()))
                .extracting("month", "teacherId", "spent", "awards", "bua", "boa")
                .containsExactlyInAnyOrder(tuple(to, petrov, 950, 3, 800, 150), tuple(to, volkov, 100, 1, 100, 0));
        assertThat(statistics.summarize(group, YearMonth.of(2026, 10), YearMonth.of(2026, 10), Optional.of(petrov))).isEmpty();
    }

    @Test
    void novemberAndDecemberAwardsUseMoscowMonth() {
        long a = member("a", "Антонов Антон", "2026-11-01T00:00:00");
        budgetAward(petrov, a, "BUA", 800, "2026-11-30T23:30:00");
        budgetAward(petrov, a, "BOA", 150, "2026-12-01T00:10:00");
        assertThat(statistics.summarize(group, YearMonth.of(2026, 11), YearMonth.of(2026, 12), Optional.of(petrov)))
                .extracting("month", "teacherId", "spent", "awards", "bua", "boa")
                .containsExactlyInAnyOrder(tuple(YearMonth.of(2026, 11), petrov, 800, 1, 800, 0),
                        tuple(YearMonth.of(2026, 12), petrov, 150, 1, 0, 150));
    }
}

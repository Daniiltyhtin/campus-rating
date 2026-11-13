package ru.campusrating.ledger;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.campusrating.support.AnalyticsTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class LedgerAnalyticsIT extends AnalyticsTestSupport {
    @Autowired LedgerApi ledger;

    @Test
    void ae1DailyChangesAndBalancesAtInclusiveInstant() {
        long a = member("a", "Антонов Антон", "2026-11-01T00:00:00");
        entry(a, "AR", "INITIAL", 1000, "2026-11-01T00:00:00");
        entry(a, "AR", "PENALTY", -20, "2026-11-02T12:00:00");
        assertThat(ledger.arBalancesAt(List.of(a), moscow("2026-11-02T11:59:59"))).containsEntry(a, 1000);
        assertThat(ledger.arBalancesAt(List.of(a), moscow("2026-11-02T12:00:00"))).containsEntry(a, 980);
        assertThat(ledger.arChangesByDay(List.of(a), LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02")))
                .extracting("studentId", "date", "amount")
                .containsExactlyInAnyOrder(tuple(a, LocalDate.parse("2026-11-01"), 1000), tuple(a, LocalDate.parse("2026-11-02"), -20));
    }

    @Test
    void ae3BucketsUseMoscowMidnightRatherThanUtc() {
        long c = member("c", "C", "2026-11-03T23:59:59");
        entry(c, "AR", "INITIAL", 1000, "2026-11-03T23:59:59");
        entry(c, "AR", "PENALTY", -5, "2026-11-04T00:00:00");
        assertThat(ledger.arChangesByDay(List.of(c), LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-04")))
                .extracting("studentId", "date", "amount")
                .containsExactlyInAnyOrder(tuple(c, LocalDate.parse("2026-11-03"), 1000), tuple(c, LocalDate.parse("2026-11-04"), -5));
        assertThat(ledger.arBalancesAt(List.of(c), moscow("2026-11-03T23:59:59"))).containsEntry(c, 1000);
    }

    @Test
    void ae4AwardsExcludeOctoberAndSeparateCurrenciesAndStudents() {
        long a = member("a", "Антонов Антон", "2026-10-31T00:00:00");
        long b = member("b", "Борисов Борис", "2026-11-01T00:00:00");
        entry(a, "BUA", "ACTIVITY_AWARD", 100, "2026-10-31T12:00:00");
        entry(a, "BUA", "ACTIVITY_AWARD", 30, "2026-11-01T00:00:00");
        entry(b, "BUA", "ACTIVITY_AWARD", 10, "2026-11-13T12:00:00");
        entry(b, "BOA", "ACTIVITY_AWARD", 50, "2026-11-13T23:59:59");
        assertThat(ledger.activityAwardTotals(List.of(a, b), LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-13")))
                .extracting("studentId", "currency", "amount")
                .containsExactlyInAnyOrder(tuple(a, Currency.BUA, 30), tuple(b, Currency.BUA, 10), tuple(b, Currency.BOA, 50));
    }

    @Test
    void ae5LatestStartedSemesterExcludesFutureAndReturnsEmptyBeforeAnyStart() {
        Timestamp earliest = jdbc.queryForObject("select min(starts_at) from semester", Timestamp.class);
        assertThat(ledger.latestSemesterStart(earliest.toInstant().minusSeconds(1))).isEmpty();
        jdbc.update("insert into semester(title,starts_at) values (?,?)", prefix + "Весна 2026", Timestamp.from(moscow("2026-02-09T00:00:00")));
        assertThat(ledger.latestSemesterStart(clock.instant())).contains(moscow("2026-09-01T00:00:00"));
    }
}

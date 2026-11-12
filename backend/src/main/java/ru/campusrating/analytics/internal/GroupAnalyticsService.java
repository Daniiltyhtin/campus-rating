package ru.campusrating.analytics.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.activity.BudgetStatistics;
import ru.campusrating.analytics.api.GroupAnalyticsController.*;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.GroupReadAccess;
import ru.campusrating.identity.Role;
import ru.campusrating.identity.StudentMember;
import ru.campusrating.ledger.Currency;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.settings.SettingsApi;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class GroupAnalyticsService {
    private final GroupReadAccess access;
    private final GroupMembers members;
    private final CurrentUser current;
    private final LedgerApi ledger;
    private final BudgetStatistics statistics;
    private final SettingsApi settings;
    private final Clock clock;

    public GroupAnalyticsService(GroupReadAccess access, GroupMembers members, CurrentUser current,
            LedgerApi ledger, BudgetStatistics statistics, SettingsApi settings, Clock clock) {
        this.access = access;
        this.members = members;
        this.current = current;
        this.ledger = ledger;
        this.statistics = statistics;
        this.settings = settings;
        this.clock = clock;
    }

    public GroupAnalytics get(long groupId, String from, String to) {
        var group = access.require(groupId);
        var period = AnalyticsPeriod.parse(from, to, LocalDate.now(clock.withZone(AnalyticsPeriod.MOSCOW)));
        var students = members.activeStudents(List.of(groupId)).stream()
                .filter(student -> !student.activatedAt().isAfter(AnalyticsPeriod.end(period.to()))).toList();
        var semester = ledger.latestSemesterStart(clock.instant()).map(at -> at.atZone(AnalyticsPeriod.MOSCOW).toLocalDate()).orElse(null);
        var ref = new StudyGroupRef(group.id(), group.name(), new FacultyRef(group.facultyId(), group.facultyCode(), group.facultyName()));
        if (students.isEmpty()) return new GroupAnalytics(ref, semester, List.of(), List.of());
        var ids = students.stream().map(StudentMember::studentId).toList();
        var balances = new HashMap<>(ledger.arBalancesAt(ids, period.from().atStartOfDay(AnalyticsPeriod.MOSCOW).toInstant().minusNanos(1000)));
        var changes = ledger.arChangesByDay(ids, period.from(), period.to()).stream()
                .collect(java.util.stream.Collectors.groupingBy(ru.campusrating.ledger.ArDayChange::date));
        var days = new ArrayList<AnalyticsDay>();
        for (var date = period.from(); !date.isAfter(period.to()); date = date.plusDays(1)) {
            for (var change : changes.getOrDefault(date, List.of())) balances.merge(change.studentId(), change.amount(), Integer::sum);
            days.add(day(date, students, balances));
        }
        var awards = new HashMap<Long, Map<Currency, Integer>>();
        ledger.activityAwardTotals(ids, period.from(), period.to()).forEach(total ->
                awards.computeIfAbsent(total.studentId(), key -> new HashMap<>()).put(total.currency(), total.amount()));
        var items = students.stream().map(student -> {
            var totals = awards.getOrDefault(student.studentId(), Map.of());
            return new AnalyticsPoints(new UserRef(student.studentId(), student.fullName()),
                    totals.getOrDefault(Currency.BUA, 0), totals.getOrDefault(Currency.BOA, 0), balances.get(student.studentId()));
        }).toList();
        return new GroupAnalytics(ref, semester, days, items);
    }

    private static AnalyticsDay day(LocalDate date, List<StudentMember> students, Map<Long, Integer> balances) {
        var active = students.stream().filter(student -> !student.activatedAt().isAfter(AnalyticsPeriod.end(date))).toList();
        long sum = active.stream().mapToLong(student -> balances.get(student.studentId())).sum();
        var average = active.isEmpty() ? null : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(active.size()), 1, RoundingMode.HALF_UP);
        return new AnalyticsDay(date, average, active.size());
    }

    public GroupAnalyticsBudget budget(long groupId) {
        access.require(groupId);
        var viewer = current.require();
        var month = YearMonth.now(clock.withZone(AnalyticsPeriod.MOSCOW));
        var teacher = viewer.role() == Role.TEACHER ? Optional.of(viewer.id()) : Optional.<Long>empty();
        var stats = statistics.summarize(groupId, month.minusMonths(5), month, teacher);
        var ids = new ArrayList<>(stats.stream().map(BudgetStatistics.Month::teacherId).toList());
        teacher.ifPresent(ids::add);
        var users = members.users(ids);
        int limit = settings.monthlyBudgetLimit();
        var rows = new ArrayList<AnalyticsBudgetMonth>();
        for (int i = 0; i < 6; i++) {
            var selected = month.minusMonths(i);
            var found = stats.stream().filter(row -> row.month().equals(selected))
                    .sorted(Comparator.comparing((BudgetStatistics.Month row) -> users.get(row.teacherId()).fullName())
                            .thenComparingLong(BudgetStatistics.Month::teacherId)).toList();
            if (found.isEmpty()) {
                var user = teacher.map(users::get).map(value -> new UserRef(value.id(), value.fullName())).orElse(null);
                rows.add(new AnalyticsBudgetMonth(selected.toString(), user, limit, 0, user == null ? null : limit, 0, 0, 0));
            } else {
                for (var row : found) {
                    var user = users.get(row.teacherId());
                    rows.add(new AnalyticsBudgetMonth(selected.toString(), new UserRef(user.id(), user.fullName()), limit,
                            row.spent(), Math.max(0, limit - row.spent()), row.awards(), row.bua(), row.boa()));
                }
            }
        }
        return new GroupAnalyticsBudget(rows);
    }
}

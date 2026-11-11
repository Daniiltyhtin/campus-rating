package ru.campusrating.activity.internal;

import java.time.Clock;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.activity.api.ActivityController.UserRef;
import ru.campusrating.activity.api.BudgetController.ActivityRef;
import ru.campusrating.activity.api.BudgetController.AwardSummaryLine;
import ru.campusrating.activity.api.BudgetController.BudgetList;
import ru.campusrating.activity.api.BudgetController.GroupBudget;
import ru.campusrating.activity.api.BudgetController.TeacherAward;
import ru.campusrating.activity.api.BudgetController.TeacherAwardPage;
import ru.campusrating.activity.api.BudgetController.TeacherAwardSummary;
import ru.campusrating.activity.domain.BudgetPolicy;
import ru.campusrating.activity.internal.AwardRepository.HistoryRow;
import ru.campusrating.activity.internal.AwardRepository.SummaryRow;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.GroupRef;
import ru.campusrating.identity.TeacherScope;
import ru.campusrating.settings.SettingsApi;
import ru.campusrating.shared.domain.MoscowTime;
import ru.campusrating.shared.error.FieldError;
import ru.campusrating.shared.error.ValidationFailedException;

@Service
public class BudgetService {
    private final ActivityRepository activities;
    private final BudgetRepository budgets;
    private final AwardRepository awards;
    private final CurrentUser current;
    private final TeacherScope scope;
    private final GroupMembers members;
    private final SettingsApi settings;
    private final Clock clock;

    BudgetService(ActivityRepository activities, BudgetRepository budgets, AwardRepository awards, CurrentUser current,
                  TeacherScope scope, GroupMembers members, SettingsApi settings, Clock clock) {
        this.activities = activities;
        this.budgets = budgets;
        this.awards = awards;
        this.current = current;
        this.scope = scope;
        this.members = members;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BudgetList budgets(@Nullable String month) {
        var user = current.require();
        var period = month(month);
        var groupIds = new TreeSet<>(scope.accessibleGroupIds(user.id()));
        groupIds.addAll(activities.authorGroups(user.id()));
        var spent = budgets.spent(user.id(), groupIds, period.atDay(1));
        int limit = settings.monthlyBudgetLimit();
        var items = members.groups(groupIds).values().stream()
                .sorted(ActivityViews.BY_NAME)
                .map(group -> budget(group, limit, spent.getOrDefault(group.id(), 0)))
                .toList();
        return new BudgetList(period.toString(), items);
    }

    @Transactional(readOnly = true)
    public TeacherAwardPage awards(@Nullable Long groupId, @Nullable String month, @Nullable String cursor, int limit) {
        var user = current.require();
        var period = month(month);
        String filter = "awards/" + user.id() + "/" + (groupId == null ? "ALL" : groupId) + "/" + period;
        var after = cursor == null ? null : cursor(cursor, filter);
        var rows = awards.history(user.id(), groupId, MonthRange.of(period), after, limit + 1);
        var page = rows.subList(0, Math.min(limit, rows.size()));
        String next = null;
        if (rows.size() > limit) {
            next = new AwardCursor(page.getLast().confirmedAt(), page.getLast().id(), filter).encode();
        }
        return new TeacherAwardPage(views(page), next);
    }

    @Transactional(readOnly = true)
    public TeacherAwardSummary summary(@Nullable String month) {
        var user = current.require();
        var period = month(month);
        var rows = awards.summary(user.id(), MonthRange.of(period));
        var groups = members.groups(rows.stream().map(SummaryRow::groupId).distinct().toList());
        var order = Comparator.comparing((SummaryRow row) -> groups.get(row.groupId()), ActivityViews.BY_NAME)
                .thenComparing(row -> row.currency().name());
        var items = rows.stream()
                .sorted(order)
                .map(row -> new AwardSummaryLine(ActivityViews.group(groups.get(row.groupId())), row.currency(), row.amount(), row.count()))
                .toList();
        int total = rows.stream().mapToInt(SummaryRow::amount).sum();
        int count = rows.stream().mapToInt(SummaryRow::count).sum();
        return new TeacherAwardSummary(period.toString(), total, count, items);
    }

    private YearMonth month(@Nullable String month) {
        return month == null ? MoscowTime.month(clock.instant()) : YearMonth.parse(month);
    }

    private GroupBudget budget(GroupRef group, int limit, int spent) {
        return new GroupBudget(ActivityViews.group(group), limit, spent, BudgetPolicy.remaining(limit, spent));
    }

    private AwardCursor cursor(String cursor, String filter) {
        try {
            return AwardCursor.decode(cursor, filter);
        } catch (IllegalArgumentException exception) {
            var error = new FieldError("cursor", "INVALID", "Курсор не подходит к списку");
            throw new ValidationFailedException("Проверьте параметры запроса", List.of(error));
        }
    }

    private List<TeacherAward> views(List<HistoryRow> rows) {
        var students = members.users(rows.stream().map(HistoryRow::studentId).distinct().toList());
        var groups = members.groups(rows.stream().map(HistoryRow::groupId).distinct().toList());
        return rows.stream()
                .map(row -> view(row, students.get(row.studentId()).fullName(), groups))
                .toList();
    }

    private TeacherAward view(HistoryRow row, String studentName, Map<Long, GroupRef> groups) {
        return new TeacherAward(
                row.id(),
                new ActivityRef(row.activityId(), row.activityTitle(), row.activityType()),
                new UserRef(row.studentId(), studentName),
                ActivityViews.group(groups.get(row.groupId())),
                row.level(),
                row.amount(),
                row.currency(),
                row.confirmedAt());
    }
}

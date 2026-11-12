package ru.campusrating.activity.internal;

import java.time.Clock;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.activity.api.AdminBudgetsController.AdminBudget;
import ru.campusrating.directory.DirectoryApi;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.activity.api.ActivityController.StudyGroupRef;
import ru.campusrating.settings.SettingsApi;
import ru.campusrating.shared.domain.MoscowTime;
import ru.campusrating.shared.error.*;

@Service
public class AdminBudgetService {
    private record Row(long teacher,long group,int spent) {}
    private final JdbcClient jdbc; private final GroupMembers members; private final DirectoryApi directory;
    private final SettingsApi settings; private final Clock clock;
    public AdminBudgetService(JdbcClient jdbc,GroupMembers members,DirectoryApi directory,SettingsApi settings,Clock clock) { this.jdbc=jdbc;this.members=members;this.directory=directory;this.settings=settings;this.clock=clock; }
    @Transactional(readOnly=true) public List<AdminBudget> list(String month) {
        YearMonth period;
        try { period=month==null?MoscowTime.month(clock.instant()):YearMonth.parse(month); }
        catch(java.time.DateTimeException e) { throw new ValidationFailedException("Проверьте месяц",List.of(new FieldError("month","INVALID","Укажите месяц в формате ГГГГ-ММ"))); }
        var rows=jdbc.sql("select teacher_id,group_id,spent from activity_budget where month=:month and spent>0")
                .param("month",period.atDay(1)).query((rs,n)->new Row(rs.getLong(1),rs.getLong(2),rs.getInt(3))).list();
        var users=members.users(rows.stream().map(Row::teacher).distinct().toList());
        var groups=directory.groups(rows.stream().map(Row::group).distinct().toList());int limit=settings.monthlyBudgetLimit();
        return rows.stream().map(r->{var g=groups.get(r.group());return new AdminBudget(users.get(r.teacher()),new StudyGroupRef(g.id(),g.name(),new ru.campusrating.activity.api.ActivityController.FacultyRef(g.faculty().id(),g.faculty().code(),g.faculty().name())),limit,r.spent(),Math.max(0,limit-r.spent()));})
                .sorted(Comparator.comparing((AdminBudget b)->b.teacher().fullName()).thenComparing(b->b.group().name()).thenComparingLong(b->b.teacher().id()).thenComparingLong(b->b.group().id())).toList();
    }
    public int impact(int limit) {
        return jdbc.sql("select count(*) from activity_budget where month=:month and spent>:limit")
                .param("month",MoscowTime.month(clock.instant()).atDay(1)).param("limit",limit).query(Integer.class).single();
    }
}

package ru.campusrating.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

public abstract class AnalyticsTestSupport extends AdminHttpTestSupport {
    @Autowired private PasswordEncoder passwords;
    protected long petrov;
    protected long volkov;
    protected Browser teacherSession;

    @BeforeEach
    void analyticsFixture() {
        clock.set(Instant.parse("2026-11-13T09:00:00Z"));
        jdbc.update("update study_group set name='ИВТ-21' where id=?", group);
        petrov = teacher("petrov", "Петров");
        volkov = teacher("volkov", "Волков");
        approve(petrov, department);
        approve(volkov, department);
        teacherSession = login("petrov");
    }

    @AfterEach
    void cleanupAnalytics() {
        jdbc.update("delete from activity_award where author_id in (?,?)", petrov, volkov);
        jdbc.update("delete from activity_budget where teacher_id in (?,?)", petrov, volkov);
        jdbc.update("delete from settings where key='monthlyBudgetLimit'");
        jdbc.update("delete from semester_accrual where semester_id in (select id from semester where title like ?)", prefix + "%");
        jdbc.update("delete from semester where title like ?", prefix + "%");
    }

    protected static Instant moscow(String time) {
        return LocalDateTime.parse(time).atZone(ZoneId.of("Europe/Moscow")).toInstant();
    }

    protected long member(String suffix, String name, String activation) {
        long id = jdbc.queryForObject("""
                insert into app_user(email,role,full_name,password_hash) values (?,'STUDENT',?,?) returning id
                """, Long.class, email(suffix), name, passwords.encode(PASSWORD));
        jdbc.update("insert into group_membership(student_id,group_id,state,activated_at) values (?,?,?,?)",
                id, group, activation == null ? "PENDING" : "ACTIVE", activation == null ? null : Timestamp.from(moscow(activation)));
        return id;
    }

    protected void entry(long student, String currency, String kind, int amount, String time) {
        String source = kind.equals("INITIAL") ? "REGISTRATION" : kind.equals("PENALTY") ? "CHECKPOINT" : "ACTIVITY";
        jdbc.update("""
                insert into ledger_entry(student_id,currency,kind,amount,source_type,source_id,source_title,
                author_id,author_name,idempotency_key,created_at) values (?,?,?,?,?,1,'Аналитика',?,?,?,?)
                """, student, currency, kind, amount, source,
                kind.equals("ACTIVITY_AWARD") ? petrov : null, kind.equals("ACTIVITY_AWARD") ? "Петров" : null,
                UUID.randomUUID().toString(), Timestamp.from(moscow(time)));
    }

    protected void budgetAward(long author, long student, String currency, int amount, String time) {
        Timestamp at = Timestamp.from(moscow(time));
        long activity = jdbc.queryForObject("""
                insert into activity(author_id,title,description,type,starts_at,max_points,created_at)
                values (?,'Аналитика','',?,?,?,?) returning id
                """, Long.class, author, currency.equals("BUA") ? "ACADEMIC" : "SOCIAL", at, amount, at);
        jdbc.update("insert into activity_group(activity_id,group_id) values (?,?)", activity, group);
        jdbc.update("""
                insert into activity_award(activity_id,student_id,group_id,level,amount,currency,author_id,confirmed_at)
                values (?,?,?,100,?,?,?,?)
                """, activity, student, group, amount, currency, author, at);
        jdbc.update("""
                insert into activity_budget(teacher_id,group_id,month,spent) values (?,?,?,?)
                on conflict (teacher_id,group_id,month) do update set spent=activity_budget.spent+excluded.spent
                """, author, group, java.sql.Date.valueOf(moscow(time).atZone(ZoneId.of("Europe/Moscow")).toLocalDate().withDayOfMonth(1)), amount);
    }

    protected void novemberBudget(long student) {
        budgetAward(petrov, student, "BUA", 400, "2026-11-01T12:00:00");
        budgetAward(petrov, student, "BUA", 400, "2026-11-02T12:00:00");
        budgetAward(petrov, student, "BOA", 150, "2026-11-03T12:00:00");
        budgetAward(volkov, student, "BUA", 100, "2026-11-03T12:00:00");
    }
}

package ru.campusrating.ledger.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class SemesterRepository {
    record Semester(long id, String title, Instant startsAt, int accrual) {}

    private final JdbcClient jdbc;

    SemesterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @org.springframework.transaction.annotation.Transactional
    List<Semester> started(Instant now, int nominal) {
        AdminSemesterService.lock(jdbc);
        jdbc.sql("update semester set accrual=:nominal where starts_at<=:now and accrual is null")
                .param("nominal",nominal).param("now",Timestamp.from(now)).update();
        return jdbc.sql("select id, title, starts_at, accrual from semester where starts_at <= :now order by starts_at, id")
                .param("now", Timestamp.from(now))
                .query((rs, row) -> new Semester(rs.getLong("id"), rs.getString("title"), rs.getTimestamp("starts_at").toInstant(),rs.getInt("accrual"))).list();
    }

    List<Long> candidates(Semester semester, long afterStudentId) {
        return jdbc.sql("""
                select b.student_id from ledger_balance b
                where b.currency = 'AR' and b.student_id > :after
                  and exists (
                      select 1 from ledger_entry e
                      where e.student_id = b.student_id and e.kind = 'INITIAL' and e.created_at <= :startsAt
                  )
                  and not exists (
                      select 1 from semester_accrual a
                      where a.semester_id = :semester and a.student_id = b.student_id
                  )
                order by b.student_id limit 500
                """).param("semester", semester.id()).param("startsAt", Timestamp.from(semester.startsAt())).param("after", afterStudentId).query(Long.class).list();
    }

    boolean recordAccrual(long semesterId, long studentId, int accrued, Instant processedAt) {
        return jdbc.sql("""
                insert into semester_accrual (semester_id, student_id, accrued, processed_at)
                values (:semester, :student, :accrued, :processedAt)
                on conflict do nothing
                """).param("semester", semesterId).param("student", studentId).param("accrued", accrued)
                .param("processedAt", Timestamp.from(processedAt)).update() == 1;
    }

    java.util.Optional<Instant> latestStart(Instant at) {
        return jdbc.sql("select starts_at from semester where starts_at <= :at order by starts_at desc, id desc limit 1")
                .param("at", Timestamp.from(at)).query((rs, row) -> rs.getTimestamp("starts_at").toInstant()).optional();
    }

}

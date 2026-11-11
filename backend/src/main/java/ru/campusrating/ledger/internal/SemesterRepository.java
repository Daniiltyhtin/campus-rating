package ru.campusrating.ledger.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class SemesterRepository {
    record Semester(long id, String title) {}

    private final JdbcClient jdbc;

    SemesterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Semester> started(Instant now) {
        return jdbc.sql("select id, title from semester where starts_at <= :now order by starts_at, id")
                .param("now", Timestamp.from(now))
                .query((rs, row) -> new Semester(rs.getLong("id"), rs.getString("title"))).list();
    }

    List<Long> candidates(long semesterId, long afterStudentId) {
        return jdbc.sql("""
                select b.student_id from ledger_balance b
                where b.currency = 'AR' and b.student_id > :after
                  and not exists (
                      select 1 from semester_accrual a
                      where a.semester_id = :semester and a.student_id = b.student_id
                  )
                order by b.student_id limit 500
                """).param("semester", semesterId).param("after", afterStudentId).query(Long.class).list();
    }

    boolean recordAccrual(long semesterId, long studentId, int accrued, Instant processedAt) {
        return jdbc.sql("""
                insert into semester_accrual (semester_id, student_id, accrued, processed_at)
                values (:semester, :student, :accrued, :processedAt)
                on conflict do nothing
                """).param("semester", semesterId).param("student", studentId).param("accrued", accrued)
                .param("processedAt", Timestamp.from(processedAt)).update() == 1;
    }
}

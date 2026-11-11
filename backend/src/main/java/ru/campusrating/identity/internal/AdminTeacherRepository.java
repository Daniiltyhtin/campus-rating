package ru.campusrating.identity.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.campusrating.identity.domain.TeacherGrant;

@Repository
class AdminTeacherRepository {
    private final JdbcClient jdbc;

    AdminTeacherRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    List<Long> teachers() {
        return jdbc.sql("select id from app_user where role='TEACHER' order by full_name,id").query(Long.class).list();
    }

    boolean lockTeacher(long id) {
        return jdbc.sql("select id from app_user where id=:id and role='TEACHER' for update")
                .param("id", id).query(Long.class).optional().isPresent();
    }

    void approve(long id, long department, long admin, Instant time) {
        jdbc.sql("update app_user set department_id=:department where id=:id")
                .param("department", department).param("id", id).update();
        jdbc.sql("insert into teacher_approval(teacher_id,approved_by,approved_at) values (:id,:admin,:time)")
                .param("id", id).param("admin", admin).param("time", Timestamp.from(time)).update();
    }

    void reject(long id) {
        jdbc.sql("delete from teacher_grant where teacher_id=:id").param("id", id).update();
        jdbc.sql("delete from teacher_approval where teacher_id=:id").param("id", id).update();
        jdbc.sql("delete from app_user where id=:id").param("id", id).update();
    }

    List<GrantRow> grants(long teacher) {
        return jdbc.sql("select * from teacher_grant where teacher_id=:teacher order by id")
                .param("teacher", teacher).query((rs, row) -> new GrantRow(rs.getLong("id"),
                        rs.getString("kind"), rs.getObject("group_id", Long.class),
                        rs.getObject("faculty_id", Long.class), rs.getObject("course", Integer.class))).list();
    }

    Optional<GrantRow> identical(long teacher, GrantRow grant) {
        return grants(teacher).stream().filter(row -> row.sameTarget(grant)).findFirst();
    }

    GrantRow insertGrant(long teacher, GrantRow grant) {
        long id = jdbc.sql("""
                insert into teacher_grant(teacher_id,kind,group_id,faculty_id,course)
                values (:teacher,:kind,:group,:faculty,:course) returning id
                """).param("teacher", teacher).param("kind", grant.kind()).param("group", grant.groupId())
                .param("faculty", grant.facultyId()).param("course", grant.course()).query(Long.class).single();
        return new GrantRow(id, grant.kind(), grant.groupId(), grant.facultyId(), grant.course());
    }

    boolean deleteGrant(long teacher, long grant) {
        return jdbc.sql("delete from teacher_grant where id=:grant and teacher_id=:teacher")
                .param("grant", grant).param("teacher", teacher).update() == 1;
    }

    record GrantRow(long id, String kind, Long groupId, Long facultyId, Integer course) {
        static GrantRow from(TeacherGrant grant) {
            return switch (grant) {
                case TeacherGrant.Group value -> new GrantRow(0, "GROUP", value.groupId(), null, null);
                case TeacherGrant.Faculty value -> new GrantRow(0, "FACULTY", null, value.facultyId(), null);
                case TeacherGrant.FacultyCourse value -> new GrantRow(0, "FACULTY_COURSE", null, value.facultyId(), value.course());
                case TeacherGrant.Course value -> new GrantRow(0, "COURSE", null, null, value.course());
            };
        }
        boolean sameTarget(GrantRow other) {
            return kind.equals(other.kind) && java.util.Objects.equals(groupId, other.groupId)
                    && java.util.Objects.equals(facultyId, other.facultyId) && java.util.Objects.equals(course, other.course);
        }
    }
}

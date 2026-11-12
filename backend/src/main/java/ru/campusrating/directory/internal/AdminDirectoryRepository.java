package ru.campusrating.directory.internal;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.campusrating.directory.DepartmentInfo;
import ru.campusrating.directory.FacultyInfo;
import ru.campusrating.directory.api.AdminDirectoryController.AdminStudyGroup;
import ru.campusrating.directory.internal.AdminDirectoryService.Kind;

@Repository
class AdminDirectoryRepository {
    private static final String GROUPS = """
            select g.*,f.code faculty_code,f.name faculty_name from study_group g
            join faculty f on f.id=g.faculty_id
            """;
    private static final org.springframework.jdbc.core.RowMapper<AdminStudyGroup> GROUP = (rs, row) ->
            new AdminStudyGroup(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                    new FacultyInfo(rs.getLong("faculty_id"), rs.getString("faculty_code"), rs.getString("faculty_name")),
                    rs.getInt("admission_year"));
    private final JdbcClient jdbc;
    AdminDirectoryRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    List<AdminStudyGroup> groups() {
        return jdbc.sql(GROUPS + " order by g.name,g.id").query(GROUP).list();
    }

    Optional<AdminStudyGroup> group(long id) {
        return jdbc.sql(GROUPS + " where g.id=:id").param("id", id).query(GROUP).optional();
    }

    List<DepartmentInfo> departments() {
        return jdbc.sql("""
                select d.*,f.code faculty_code,f.name faculty_name from department d
                join faculty f on f.id=d.faculty_id order by d.name,d.id
                """).query((rs, row) -> new DepartmentInfo(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        new FacultyInfo(rs.getLong("faculty_id"), rs.getString("faculty_code"), rs.getString("faculty_name")))).list();
    }

    boolean exists(Kind kind, long id) {
        return jdbc.sql("select exists(select 1 from " + kind.table + " where id=:id)")
                .param("id", id).query(Boolean.class).single();
    }

    boolean codeTaken(Kind kind, Long id, String code) {
        return jdbc.sql("select exists(select 1 from " + kind.table + " where code=:code and id<>:id)")
                .param("code", code).param("id", id == null ? 0 : id).query(Boolean.class).single();
    }

    Optional<Long> save(Kind kind, Long id, String code, String name, Long faculty, Integer admissionYear) {
        String extraColumns = kind == Kind.FACULTY ? "" : ",faculty_id";
        String extraValues = kind == Kind.FACULTY ? "" : ",:faculty";
        String extraAssignments = kind == Kind.FACULTY ? "" : ",faculty_id=:faculty";
        if (kind == Kind.STUDY_GROUP) {
            extraColumns += ",admission_year"; extraValues += ",:year"; extraAssignments += ",admission_year=:year";
        }
        String sql = id == null
                ? "insert into " + kind.table + "(code,name" + extraColumns + ") values (:code,:name" + extraValues + ") returning id"
                : "update " + kind.table + " set code=:code,name=:name" + extraAssignments + " where id=:id returning id";
        var statement = jdbc.sql(sql).param("code", code).param("name", name);
        if (id != null) statement.param("id", id);
        if (kind != Kind.FACULTY) statement.param("faculty", faculty);
        if (kind == Kind.STUDY_GROUP) statement.param("year", admissionYear);
        return statement.query(Long.class).optional();
    }

    boolean inUse(Kind kind, long id) {
        String predicate = switch (kind) {
            case FACULTY -> """
                    exists(select 1 from study_group where faculty_id=:id)
                    or exists(select 1 from department where faculty_id=:id)
                    or exists(select 1 from teacher_grant where faculty_id=:id)
                    """;
            case DEPARTMENT -> """
                    exists(select 1 from app_user where department_id=:id)
                    or exists(select 1 from teacher_allowlist where department_id=:id)
                    """;
            case STUDY_GROUP -> """
                    exists(select 1 from group_membership where group_id=:id)
                    or exists(select 1 from checkpoint_group where group_id=:id)
                    or exists(select 1 from assignment where group_id=:id)
                    or exists(select 1 from activity_group where group_id=:id)
                    or exists(select 1 from activity_award where group_id=:id)
                    or exists(select 1 from activity_budget where group_id=:id)
                    or exists(select 1 from teacher_grant where group_id=:id)
                    """;
        };
        return jdbc.sql("select " + predicate).param("id", id).query(Boolean.class).single();
    }

    boolean delete(Kind kind, long id) {
        return jdbc.sql("delete from " + kind.table + " where id=:id").param("id", id).update() == 1;
    }
}

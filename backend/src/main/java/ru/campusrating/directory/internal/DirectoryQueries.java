package ru.campusrating.directory.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.campusrating.directory.DepartmentInfo;
import ru.campusrating.directory.FacultyInfo;
import ru.campusrating.directory.DirectoryApi;
import ru.campusrating.directory.GroupInfo;

@Repository
public class DirectoryQueries implements DirectoryApi {
    private static final String GROUPS = """
            select g.id, g.name, g.admission_year, f.id faculty_id, f.code faculty_code, f.name faculty_name
            from study_group g join faculty f on f.id = g.faculty_id
            """;
    private static final String DEPARTMENTS = """
            select d.id, d.code, d.name, f.id faculty_id, f.code faculty_code, f.name faculty_name
            from department d join faculty f on f.id = d.faculty_id
            """;
    private static final RowMapper<GroupInfo> GROUP_MAPPER = (rs, row) ->
            new GroupInfo(rs.getLong("id"), rs.getString("name"), facultyOf(rs), rs.getInt("admission_year"));
    private static final RowMapper<DepartmentInfo> DEPARTMENT_MAPPER = (rs, row) ->
            new DepartmentInfo(rs.getLong("id"), rs.getString("code"), rs.getString("name"), facultyOf(rs));
    private final JdbcClient jdbc;

    public DirectoryQueries(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<FacultyInfo> faculty(long facultyId) {
        return jdbc.sql("select id, code, name from faculty where id = :id").param("id", facultyId)
                .query((rs, row) -> new FacultyInfo(rs.getLong("id"), rs.getString("code"), rs.getString("name"))).optional();
    }

    public List<FacultyInfo> faculties() {
        return jdbc.sql("select id, code, name from faculty order by name, id")
                .query((rs, row) -> new FacultyInfo(rs.getLong("id"), rs.getString("code"), rs.getString("name"))).list();
    }

    public boolean facultyExists(long id) {
        return jdbc.sql("select exists(select 1 from faculty where id = :id)")
                .param("id", id).query(Boolean.class).single();
    }

    @Override
    public Optional<GroupInfo> group(long groupId) {
        return jdbc.sql(GROUPS + " where g.id = :id").param("id", groupId).query(GROUP_MAPPER).optional();
    }

    @Override
    public Map<Long, GroupInfo> groups(Collection<Long> groupIds) {
        if (groupIds.isEmpty()) { return Map.of(); }
        return jdbc.sql(GROUPS + " where g.id in (:ids)").param("ids", groupIds).query(GROUP_MAPPER).list()
                .stream().collect(Collectors.toMap(GroupInfo::id, Function.identity()));
    }

    @Override
    public List<GroupInfo> groupsOfFaculty(long facultyId) {
        return jdbc.sql(GROUPS + " where g.faculty_id = :id order by g.name, g.id")
                .param("id", facultyId).query(GROUP_MAPPER).list();
    }

    @Override
    public List<GroupInfo> allGroups() {
        return jdbc.sql(GROUPS + " order by g.name, g.id").query(GROUP_MAPPER).list();
    }

    @Override
    public Optional<DepartmentInfo> department(long departmentId) {
        return jdbc.sql(DEPARTMENTS + " where d.id = :id").param("id", departmentId)
                .query(DEPARTMENT_MAPPER).optional();
    }

    public List<DepartmentInfo> departmentsOfFaculty(long facultyId) {
        return jdbc.sql(DEPARTMENTS + " where d.faculty_id = :id order by d.name, d.id")
                .param("id", facultyId).query(DEPARTMENT_MAPPER).list();
    }

    private static FacultyInfo facultyOf(ResultSet rs) throws SQLException {
        return new FacultyInfo(rs.getLong("faculty_id"), rs.getString("faculty_code"), rs.getString("faculty_name"));
    }
}

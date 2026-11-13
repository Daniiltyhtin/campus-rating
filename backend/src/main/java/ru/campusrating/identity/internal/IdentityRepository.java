package ru.campusrating.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.campusrating.identity.GroupMembershipState;
import ru.campusrating.identity.Role;
import ru.campusrating.identity.StudentMember;
import ru.campusrating.identity.domain.TeacherGrant;

@Repository
class IdentityRepository {
    private static final String USERS = """
            select u.*, exists(select 1 from teacher_approval a where a.teacher_id=u.id) approved from app_user u
            """;
    private static final String MEMBERS = """
            select u.id,u.full_name,m.group_id,m.state,m.activated_at
            from group_membership m join app_user u on u.id=m.student_id where u.role='STUDENT'
            """;
    private static final RowMapper<StudentMember> MEMBER = (rs, row) -> new StudentMember(rs.getLong("id"),
            rs.getString("full_name"), rs.getLong("group_id"), GroupMembershipState.valueOf(rs.getString("state")),
            rs.getTimestamp("activated_at") == null ? null : rs.getTimestamp("activated_at").toInstant());
    private final JdbcClient jdbc;
    private final Clock clock;
    IdentityRepository(JdbcClient jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    Optional<UserAccount> byId(long id) {
        return jdbc.sql(USERS + " where u.id=:id").param("id", id).query((rs, row) -> user(rs)).optional();
    }
    Optional<UserAccount> byEmail(String email) {
        return jdbc.sql(USERS + " where lower(u.email)=lower(:email)").param("email", email).query((rs, row) -> user(rs)).optional();
    }
    List<UserAccount> users(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql(USERS + " where u.id in (:ids)").param("ids", ids).query((rs, row) -> user(rs)).list();
    }
    boolean allowlisted(String email) {
        return jdbc.sql("select exists(select 1 from teacher_allowlist where lower(email)=lower(:email))")
                .param("email", email).query(Boolean.class).single();
    }
    long insert(String email, String name, String hash, Role role, Long department, boolean mustChange) {
        return jdbc.sql("""
                insert into app_user(email,full_name,password_hash,role,department_id,must_change_password,created_at)
                values (:email,:name,:hash,:role,:department,:change,:created) returning id
                """).param("email", email).param("name", name).param("hash", hash).param("role", role.name())
                .param("department", department).param("change", mustChange).param("created", Timestamp.from(clock.instant())).query(Long.class).single();
    }
    void recordLogin(long id) {
        jdbc.sql("update app_user set last_login_at=:time where id=:id")
                .param("time", Timestamp.from(clock.instant())).param("id", id).update();
    }
    boolean addMembership(long id, long group, GroupMembershipState state, Instant activatedAt) {
        return jdbc.sql("""
                insert into group_membership(student_id,group_id,state,activated_at) values (:id,:group,:state,:time)
                on conflict(student_id) do nothing
                """).param("id", id).param("group", group).param("state", state.name())
                .param("time", activatedAt == null ? null : Timestamp.from(activatedAt)).update() == 1;
    }
    List<StudentMember> activeMembers(Collection<Long> groupIds) {
        if (groupIds.isEmpty()) return List.of();
        return jdbc.sql(MEMBERS + " and m.state='ACTIVE' and m.group_id in (:ids) order by m.group_id,u.full_name,u.id")
                .param("ids", groupIds).query(MEMBER).list();
    }
    List<StudentMember> memberships(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql(MEMBERS + " and m.student_id in (:ids)").param("ids", ids).query(MEMBER).list();
    }
    List<TeacherGrant> grants(long teacher) {
        return jdbc.sql("select kind,group_id,faculty_id,course from teacher_grant where teacher_id=:teacher")
                .param("teacher", teacher).query((rs, row) -> grant(rs)).list();
    }
    boolean changePassword(long id, String before, String hash) {
        return jdbc.sql("update app_user set password_hash=:hash,must_change_password=false where id=:id and password_hash=:before")
                .param("hash", hash).param("id", id).param("before", before).update() == 1;
    }
    int replacePasswords(Collection<Long> ids, Role role, String hash) {
        return jdbc.sql("update app_user set password_hash=:hash where id in (:ids) and role=:role")
                .param("hash", hash).param("ids", ids).param("role", role.name()).update();
    }
    boolean hasAdmin() {
        return jdbc.sql("select exists(select 1 from app_user where role='ADMIN')").query(Boolean.class).single();
    }
    private static UserAccount user(ResultSet rs) throws SQLException {
        return new UserAccount(rs.getLong("id"), rs.getString("email"), Role.valueOf(rs.getString("role")),
                rs.getString("full_name"), rs.getString("password_hash"), rs.getBoolean("must_change_password"),
                rs.getObject("department_id", Long.class), rs.getBoolean("approved"));
    }
    private static TeacherGrant grant(ResultSet rs) throws SQLException {
        return switch (rs.getString("kind")) {
            case "GROUP" -> new TeacherGrant.Group(rs.getLong("group_id"));
            case "FACULTY" -> new TeacherGrant.Faculty(rs.getLong("faculty_id"));
            case "FACULTY_COURSE" -> new TeacherGrant.FacultyCourse(rs.getLong("faculty_id"), rs.getInt("course"));
            case "COURSE" -> new TeacherGrant.Course(rs.getInt("course"));
            default -> throw new IllegalArgumentException("Unknown teacher grant kind");
        };
    }
}

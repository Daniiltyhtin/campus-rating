package ru.campusrating.identity.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.campusrating.identity.GroupMembershipState;

@Repository
class MembershipRepository {
    record Locked(long studentId, long groupId, GroupMembershipState state) {}
    record Pending(long studentId, String fullName, String email, long groupId, Instant requestedAt) {}
    record Head(long groupId, long studentId, String fullName) {}

    private final JdbcClient jdbc;
    MembershipRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    Optional<Locked> lock(long studentId) {
        return jdbc.sql("""
                select m.student_id,m.group_id,m.state from group_membership m join app_user u on u.id=m.student_id
                where m.student_id=:id and u.role='STUDENT' for update of m
                """).param("id", studentId)
                .query((rs, row) -> new Locked(rs.getLong("student_id"), rs.getLong("group_id"),
                        GroupMembershipState.valueOf(rs.getString("state")))).optional();
    }
    void activate(long studentId, Instant time) {
        jdbc.sql("update group_membership set state='ACTIVE',activated_at=:time where student_id=:id")
                .param("id", studentId).param("time", Timestamp.from(time)).update();
    }
    void reject(long studentId) {
        jdbc.sql("update group_membership set state='REJECTED',activated_at=null where student_id=:id").param("id", studentId).update();
    }
    void reapply(long studentId, long groupId, Instant time) {
        jdbc.sql("update group_membership set state='PENDING',activated_at=null,group_id=:group,requested_at=:time where student_id=:id")
                .param("id", studentId).param("group", groupId).param("time", Timestamp.from(time)).update();
    }
    List<Pending> pending(Collection<Long> groupIds) {
        if (groupIds.isEmpty()) return List.of();
        return jdbc.sql("""
                select m.student_id,u.full_name,u.email,m.group_id,m.requested_at from group_membership m
                join app_user u on u.id=m.student_id
                where m.state='PENDING' and u.role='STUDENT' and m.group_id in (:groups)
                order by m.requested_at,m.student_id
                """).param("groups", groupIds)
                .query((rs, row) -> new Pending(rs.getLong("student_id"), rs.getString("full_name"), rs.getString("email"),
                        rs.getLong("group_id"), rs.getTimestamp("requested_at").toInstant())).list();
    }
    Optional<Long> headedGroup(long studentId) {
        return jdbc.sql("select group_id from group_head where student_id=:id").param("id", studentId).query(Long.class).optional();
    }
    List<Head> heads() {
        return jdbc.sql("select h.group_id,h.student_id,u.full_name from group_head h join app_user u on u.id=h.student_id order by h.group_id")
                .query((rs, row) -> new Head(rs.getLong("group_id"), rs.getLong("student_id"), rs.getString("full_name"))).list();
    }
    void assign(long groupId, long studentId, Instant time) {
        jdbc.sql("""
                insert into group_head(group_id,student_id,assigned_at) values (:group,:student,:time)
                on conflict (group_id) do update set student_id=excluded.student_id,assigned_at=excluded.assigned_at
                """).param("group", groupId).param("student", studentId).param("time", Timestamp.from(time)).update();
    }
    void assignIfAbsent(long groupId, long studentId, Instant time) {
        jdbc.sql("insert into group_head(group_id,student_id,assigned_at) values (:group,:student,:time) on conflict do nothing")
                .param("group", groupId).param("student", studentId).param("time", Timestamp.from(time)).update();
    }
    boolean remove(long groupId) {
        return jdbc.sql("delete from group_head where group_id=:group").param("group", groupId).update() == 1;
    }
}

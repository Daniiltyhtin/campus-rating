package ru.campusrating.identity.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.campusrating.directory.DirectoryApi;
import ru.campusrating.identity.*;
import ru.campusrating.identity.api.AdminUsersController.*;
import ru.campusrating.identity.api.AuthDtos.StudyGroupRef;
import ru.campusrating.shared.error.*;

@Service
public class AdminUserService {
    private static final String AUDIT="(select max(a.created_at) from temporary_password_audit a where a.user_id=u.id)";
    private final JdbcClient jdbc; private final DirectoryApi directory; private final IdentityRepository users;
    private final CurrentUser current; private final TeacherScope scope; private final Passwords passwords;
    private final TemporaryPassword generator; private final SessionRegistry sessions; private final Clock clock;
    public AdminUserService(JdbcClient jdbc,DirectoryApi directory,IdentityRepository users,CurrentUser current,
            TeacherScope scope,Passwords passwords,TemporaryPassword generator,SessionRegistry sessions,Clock clock) {
        this.jdbc=jdbc;this.directory=directory;this.users=users;this.current=current;this.scope=scope;this.passwords=passwords;this.generator=generator;this.sessions=sessions;this.clock=clock;
    }
    private static final String STATUS = """
            case when u.role='TEACHER' then
              case when exists(select 1 from teacher_approval a where a.teacher_id=u.id) then 'APPROVED' else 'PENDING' end
            when u.role='STUDENT' then 'MEMBERSHIP_' || m.state end
            """;
    private static final String UNIT="coalesce((select g.name from study_group g where g.id=m.group_id),(select d.name from department d where d.id=u.department_id))";
    private static final String ROLE_RANK="case u.role when 'ADMIN' then 1 when 'TEACHER' then 2 else 3 end";
    private static final String STATUS_RANK="case "+STATUS+" when 'PENDING' then 1 when 'MEMBERSHIP_PENDING' then 2 when 'APPROVED' then 3 when 'MEMBERSHIP_ACTIVE' then 4 end";

    @Transactional(readOnly=true)
    public AdminUserPage list(String query,Role role,UserStatus status,UserSort sort,SortDirection direction,String cursor) {
        if(current.require().role()!=Role.ADMIN) throw forbidden();
        String search=query==null?"":query.strip().toLowerCase(Locale.ROOT);
        if(search.length()<2) search="";
        SortDirection order=direction!=null?direction:sort==UserSort.created?SortDirection.desc:SortDirection.asc;
        String filter=search+"\0"+(role==null?"":role.name())+"\0"+(status==null?"":status.name())+"\0"+sort.name()+"\0"+order.name();
        int offset=offset(cursor,filter);
        String sql="select u.*,m.group_id,"+AUDIT+" password_set_at,"+STATUS+" user_status from app_user u "+
                "left join group_membership m on m.student_id=u.id where "+
                "(:search='' or strpos(lower(u.full_name),:search)>0 or strpos(lower(u.email),:search)>0) "+
                "and (:role='' or u.role=:role) and (:status='' or ("+STATUS+")=:status) "+
                "order by "+order(sort,order)+" limit 51 offset :offset";
        var rows=jdbc.sql(sql).param("search",search).param("role",role==null?"":role.name())
                .param("status",status==null?"":status.name()).param("offset",offset)
                .query((rs,n)->new AdminUser(rs.getLong("id"),rs.getString("full_name"),rs.getString("email"),Role.valueOf(rs.getString("role")),
                group(rs.getObject("group_id",Long.class)),department(rs.getObject("department_id",Long.class)),
                instant(rs.getTimestamp("password_set_at")),rs.getString("user_status")==null?null:UserStatus.valueOf(rs.getString("user_status")),
                instant(rs.getTimestamp("created_at")),instant(rs.getTimestamp("last_login_at")))).list();
        var page=rows.subList(0,Math.min(rows.size(),50));
        return new AdminUserPage(page,rows.size()>50?cursor(offset+50,filter):null);
    }
    private static Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }
    private static int offset(String cursor,String filter) {
        if(cursor==null) return 0;
        try {
            var parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\0",2);
            int offset=Integer.parseInt(parts[0]);
            if(offset<=0 || offset%50!=0 || !parts[1].equals(filter)) throw new IllegalArgumentException();
            return offset;
        } catch(RuntimeException e) { throw Passwords.invalid("cursor","INVALID","Курсор не подходит к списку"); }
    }
    private static String cursor(int offset,String filter) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((offset+"\0"+filter).getBytes(StandardCharsets.UTF_8));
    }
    private static String order(UserSort sort,SortDirection direction) {
        String key=switch(sort) {
            case name -> "u.full_name";
            case email -> "lower(u.email)";
            case role -> ROLE_RANK;
            case unit -> UNIT;
            case status -> STATUS_RANK;
            case created -> "u.created_at";
            case lastLogin -> "u.last_login_at";
        };
        boolean asc=direction==SortDirection.asc;
        String nulls=sort==UserSort.lastLogin&&asc?" nulls first":" nulls last";
        return key+(asc?" asc":" desc")+nulls+",u.full_name,u.id";
    }
    private StudyGroupRef group(Long id) { return id==null?null:directory.group(id).map(g->new StudyGroupRef(g.id(),g.name(),g.faculty())).orElse(null); }
    private ru.campusrating.directory.DepartmentInfo department(Long id) { return id==null?null:directory.department(id).orElse(null); }
    public List<GroupStudent> students(long groupId) {
        var actor=current.require();if(actor.role()!=Role.ADMIN&&(actor.role()!=Role.TEACHER||!scope.accessibleGroupIds(actor.id()).contains(groupId))) throw forbidden();
        return jdbc.sql("select u.id,u.full_name,u.email,m.state,"+AUDIT+" password_set_at from app_user u join group_membership m on m.student_id=u.id where m.group_id=:group and m.state in ('ACTIVE','PENDING') order by u.full_name,u.id")
                .param("group",groupId).query((rs,n)->new GroupStudent(rs.getLong("id"),rs.getString("full_name"),rs.getString("email"),GroupMembershipState.valueOf(rs.getString("state")),rs.getTimestamp("password_set_at")==null?null:rs.getTimestamp("password_set_at").toInstant())).list();
    }
    @Transactional
    public String reset(long id) {
        var actor=current.require();
        if(actor.role()!=Role.ADMIN&&actor.role()!=Role.TEACHER) throw forbidden();
        var locked=jdbc.sql("select id from app_user where id=:id for update").param("id",id).query(Long.class).optional();
        if(locked.isEmpty()) throw new ApiException(ProblemCode.NOT_FOUND,"Пользователь не найден");
        var target=users.byId(id).orElseThrow();
        if(target.role()==Role.ADMIN) throw forbidden();
        if(actor.role()==Role.TEACHER) {
            if(target.role()!=Role.STUDENT) throw forbidden();
            var member=users.memberships(List.of(id)).stream().findFirst();
            if(member.isEmpty()||!scope.accessibleGroupIds(actor.id()).contains(member.get().groupId())) throw forbidden();
        }
        String password=generator.generate();String hash=passwords.hash(password,"password");
        jdbc.sql("update app_user set password_hash=:hash,must_change_password=true where id=:id").param("hash",hash).param("id",id).update();
        jdbc.sql("insert into temporary_password_audit(user_id,actor_id,actor_name,created_at) values(:user,:actor,:name,:time)")
                .param("user",id).param("actor",actor.id()).param("name",actor.fullName()).param("time",Timestamp.from(clock.instant())).update();
        jdbc.sql("update password_setup_token set consumed_at=:time where user_id=:id and consumed_at is null")
                .param("time",Timestamp.from(clock.instant())).param("id",id).update();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { expire(id); }
        });
        return password;
    }
    void expire(long id) {
        for(var principal:sessions.getAllPrincipals()) if(principal instanceof IdentityPrincipal p&&p.userId()==id)
            sessions.getAllSessions(principal,false).forEach(s->s.expireNow());
    }
    public Optional<PasswordAudit> audit() {
        return jdbc.sql("select actor_id,actor_name,created_at from temporary_password_audit where user_id=:id order by created_at desc,id desc limit 1")
                .param("id",current.require().id()).query((rs,n)->new PasswordAudit(new UserRef(rs.getLong("actor_id"),rs.getString("actor_name")),rs.getTimestamp("created_at").toInstant())).optional();
    }
    private ApiException forbidden() { return new ApiException(ProblemCode.FORBIDDEN,"Нет доступа"); }
}

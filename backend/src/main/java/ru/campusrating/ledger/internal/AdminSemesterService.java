package ru.campusrating.ledger.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.ledger.api.AdminSemestersController.AdminSemester;
import ru.campusrating.ledger.api.AdminSemestersController.AdminSemesterRequest;
import ru.campusrating.settings.SettingsApi;
import ru.campusrating.shared.error.*;

@Service
public class AdminSemesterService {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final SettingsApi settings;
    public AdminSemesterService(JdbcClient jdbc, Clock clock, SettingsApi settings) { this.jdbc=jdbc; this.clock=clock; this.settings=settings; }
    public List<AdminSemester> list() {
        var now=clock.instant();
        return jdbc.sql("select id,title,starts_at,accrual from semester order by starts_at,id")
                .query((rs,n)->new AdminSemester(rs.getLong("id"),rs.getString("title"),rs.getTimestamp("starts_at").toInstant(),
                        !rs.getTimestamp("starts_at").toInstant().isAfter(now),rs.getObject("accrual",Integer.class))).list();
    }
    @Transactional
    public AdminSemester save(Long id, AdminSemesterRequest body) {
        lock(jdbc);
        var all=list();
        if(id!=null) requirePlanned(find(all,id));
        if(all.stream().anyMatch(s->!Long.valueOf(s.id()).equals(id)&&s.title().equalsIgnoreCase(body.title())))
            throw invalid("title","Семестр с таким названием уже есть");
        if(!body.startsAt().isAfter(clock.instant())) throw invalid("startsAt","Начало должно быть позже текущего времени");
        if(!body.startsAt().equals(body.startsAt().truncatedTo(ChronoUnit.MINUTES))) throw invalid("startsAt","Укажите начало с точностью до минуты");
        checkOrder(all,id,body.startsAt());
        long saved=id==null?jdbc.sql("insert into semester(title,starts_at) values(:title,:start) returning id")
                .param("title",body.title()).param("start",Timestamp.from(body.startsAt())).query(Long.class).single():id;
        if(id!=null) jdbc.sql("update semester set title=:title,starts_at=:start where id=:id")
                .param("title",body.title()).param("start",Timestamp.from(body.startsAt())).param("id",id).update();
        return find(list(),saved);
    }
    @Transactional public void delete(long id) {
        lock(jdbc); requirePlanned(find(list(),id)); jdbc.sql("delete from semester where id=:id").param("id",id).update();
    }
    @Transactional public AdminSemester start(long id) {
        lock(jdbc); var all=list(); var semester=find(all,id);
        if(semester.started()) return semester;
        var nearest=all.stream().filter(s->!s.started()).findFirst().orElseThrow();
        if(nearest.id()!=id) throw new ApiException(ProblemCode.INVALID_STATE,"Начать сейчас можно только ближайший запланированный семестр");
        jdbc.sql("update semester set starts_at=:now,accrual=:n where id=:id")
                .param("now",Timestamp.from(clock.instant())).param("n",settings.semesterAccrual()).param("id",id).update();
        return find(list(),id);
    }
    private void checkOrder(List<AdminSemester> all,Long id,Instant start) {
        AdminSemester previous=null; AdminSemester next=null;
        for(int i=0;i<all.size();i++) {
            if(Long.valueOf(all.get(i).id()).equals(id)) { previous=i==0?null:all.get(i-1);next=i+1==all.size()?null:all.get(i+1);break; }
            if(id==null) previous=all.get(i);
        }
        if(previous!=null&&!start.isAfter(previous.startsAt())) throw invalid("startsAt","Начало должно быть позже начала семестра «"+previous.title()+"»");
        if(next!=null&&!start.isBefore(next.startsAt())) throw invalid("startsAt","Начало должно быть раньше начала семестра «"+next.title()+"»");
    }
    static void lock(JdbcClient jdbc) { jdbc.sql("select pg_advisory_xact_lock(13001)").query((rs,n)->true).single(); }
    private AdminSemester find(List<AdminSemester> all,long id) { return all.stream().filter(s->s.id()==id).findFirst().orElseThrow(()->new ApiException(ProblemCode.NOT_FOUND,"Семестр не найден")); }
    private void requirePlanned(AdminSemester semester) { if(semester.started()) throw new ApiException(ProblemCode.INVALID_STATE,"Семестр уже начался — изменить нельзя"); }
    private ValidationFailedException invalid(String field,String message) { return new ValidationFailedException(message,List.of(new FieldError(field,"INVALID",message))); }
}

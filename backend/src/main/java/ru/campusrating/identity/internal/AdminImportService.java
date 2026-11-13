package ru.campusrating.identity.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.campusrating.directory.DirectoryImport;
import ru.campusrating.identity.api.AdminImportController.*;
import ru.campusrating.identity.internal.ImportFileReader.Kind;
import ru.campusrating.shared.error.*;

@Service
public class AdminImportService {
    private record EmailValue(@Email String email) {}
    private final ImportFileReader reader;private final DirectoryImport directory;private final IdentityRepository users;private final ru.campusrating.directory.DirectoryApi directoryRead;
    private final RegistrationService registration;private final JdbcClient jdbc;private final Validator validator;private final PasswordSetupService setup;
    public AdminImportService(ImportFileReader reader,DirectoryImport directory,IdentityRepository users,RegistrationService registration,JdbcClient jdbc,Validator validator,PasswordSetupService setup,ru.campusrating.directory.DirectoryApi directoryRead) {
        this.reader=reader;this.directory=directory;this.users=users;this.registration=registration;this.jdbc=jdbc;this.validator=validator;this.setup=setup;this.directoryRead=directoryRead;
    }
    @Transactional public ImportReport importFile(Kind kind,MultipartFile file) {
        var rows=reader.read(kind,file);lock(jdbc);int created=0,skipped=0;var rejected=new ArrayList<ImportRejection>();
        for(var row:rows) {
            var values=row.values();String reason=structural(kind,values);String result=reason;
            if(reason==null) result=switch(kind) {case GROUPS->group(values);case STUDENTS->student(values);case TEACHER_ALLOWLIST->teacher(values);};
            if(result==null) created++;else if(result.equals("уже есть")) skipped++;
            else rejected.add(new ImportRejection(row.number(),values.size()>(kind==Kind.GROUPS?1:0)?values.get(kind==Kind.GROUPS?1:0):"",result));
        }
        return new ImportReport(created,skipped,rejected);
    }
    static void lock(JdbcClient jdbc) {jdbc.sql("select pg_advisory_xact_lock(13002)").query((rs,n)->true).single();}
    private String structural(Kind kind,List<String> row) {
        if(row.size()!=kind.header.size()) return "неверное число колонок";
        return null;
    }
    private String group(List<String> v) {
        if(v.get(0).isEmpty()) return "пустое значение faculty_code";
        var faculty=directory.facultyByCode(v.get(0));if(faculty.isEmpty()) return "факультет не найден";
        if(v.get(1).isEmpty()) return "пустое значение group_code";
        if(v.get(1).length()>64) return "неверный код группы";
        if(v.get(2).isEmpty()) return "пустое значение group_name";
        if(v.get(2).length()>255) return "неверное название группы";
        if(v.get(3).isEmpty()) return "пустое значение admission_year";
        int year;try {year=Integer.parseInt(v.get(3));if(year<2000||year>2100) return "неверный год набора";}catch(NumberFormatException e){return "неверный год набора";}
        var existing=directory.groupByCode(v.get(1));
        if(existing.isPresent()) {var g=existing.get();return g.facultyId()==faculty.get().id()&&g.name().equalsIgnoreCase(v.get(2))&&g.admissionYear()==year?"уже есть":"код группы занят";}
        directory.createGroup(faculty.get().id(),v.get(1),v.get(2),year);return null;
    }
    private String student(List<String> v) {
        if(v.get(0).isEmpty()) return "пустое значение email";
        String email=v.get(0).toLowerCase(Locale.ROOT);if(!validEmail(email)) return "неверный email";
        if(users.allowlisted(email)) return "email из списка преподавателей";
        var existing=users.byEmail(email);
        if(existing.isPresent()&&existing.get().role()!=ru.campusrating.identity.Role.STUDENT) return "email занят";
        if(v.get(1).isEmpty()) return "пустое значение last_name";
        if(v.get(2).isEmpty()) return "пустое значение first_name";
        if(v.get(1).length()>100) return "неверная фамилия";
        if(v.get(2).length()>100) return "неверное имя";
        if(v.get(3).length()>100) return "неверное отчество";
        boolean sameName=existing.map(u -> users.sameName(u.id(),v.get(1),v.get(2),v.get(3))).orElse(false);
        if(existing.isPresent()&&!sameName) return "email занят";
        if(v.get(4).isEmpty()) return "пустое значение faculty_code";
        var faculty=directory.facultyByCode(v.get(4));if(faculty.isEmpty()) return "факультет не найден";
        if(v.get(5).isEmpty()) return "пустое значение group_code";
        var group=directory.groupByCode(v.get(5));if(group.isEmpty()) return "группа не найдена";
        if(group.get().facultyId()!=faculty.get().id()) return "группа другого факультета";
        if(existing.isPresent()) {
            var membership=users.memberships(List.of(existing.get().id()));
            return sameName&&!membership.isEmpty()&&membership.getFirst().groupId()==group.get().id()?"уже есть":"email занят";
        }
        long id=registration.importStudent(email,v.get(1),v.get(2),v.get(3),faculty.get().id(),group.get().id());setup.sendSetup(id,email);return null;
    }
    private String teacher(List<String> v) {
        if(v.get(0).isEmpty()) return "пустое значение email";
        String email=v.get(0).toLowerCase(Locale.ROOT);if(!validEmail(email)) return "неверный email";
        var existing=users.byEmail(email);
        if(existing.isPresent()&&existing.get().role()!=ru.campusrating.identity.Role.TEACHER) return existing.get().role()==ru.campusrating.identity.Role.STUDENT?"email занят студентом":"email занят администратором";
        if(v.get(1).isEmpty()) return "пустое значение department_code";
        var department=directory.departmentByCode(v.get(1));if(department.isEmpty()) return "кафедра не найдена";
        var previous=jdbc.sql("select department_id from teacher_allowlist where lower(email)=:email").param("email",email).query(Long.class).optional();
        if(previous.isPresent()) return previous.get()==department.get().id()?"уже есть":"email уже в списке с другой кафедрой";
        jdbc.sql("insert into teacher_allowlist(email,department_id) values(:email,:department)").param("email",email).param("department",department.get().id()).update();return null;
    }
    private boolean validEmail(String email) {return email.length()<=254&&validator.validate(new EmailValue(email)).isEmpty();}
    public List<TeacherAllowlistEntry> allowlist() {
        return jdbc.sql("select a.email,a.department_id,exists(select 1 from app_user u where lower(u.email)=lower(a.email) and u.role='TEACHER') registered from teacher_allowlist a order by a.email")
                .query((rs,n)->new TeacherAllowlistEntry(rs.getString("email"),directoryRead.department(rs.getLong("department_id")).orElseThrow(),rs.getBoolean("registered"))).list();
    }
    @Transactional public void deleteAllowlist(String raw) {
        lock(jdbc);String email=raw.strip().toLowerCase(Locale.ROOT);
        if(users.byEmail(email).filter(u->u.role()==ru.campusrating.identity.Role.TEACHER).isPresent()) throw new ApiException(ProblemCode.INVALID_STATE,"Преподаватель уже зарегистрирован — удалить нельзя");
        if(jdbc.sql("delete from teacher_allowlist where lower(email)=:email").param("email",email).update()==0) throw new ApiException(ProblemCode.NOT_FOUND,"Запись не найдена");
    }
}

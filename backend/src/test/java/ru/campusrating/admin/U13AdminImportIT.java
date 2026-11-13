package ru.campusrating.admin;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.campusrating.support.U13HttpTestSupport;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

class U13AdminImportIT extends U13HttpTestSupport {
    private static final String IMPORT="/api/v1/admin/import/";
    private static final String ALLOWLIST="/api/v1/admin/teacher-allowlist";
    private static final String STUDENTS="email,full_name,faculty_code,group_code\r\n";
    private String facultyCode() { return prefix+"f1"; }
    private String groupCode() { return prefix+"g1"; }
    private JsonNode csv(String kind,String data) { return adminWire().upload(IMPORT+kind,"data.csv",data.getBytes(StandardCharsets.UTF_8),200); }
    private void report(JsonNode body,int created,int skipped,int rejected) {
        assertThat(body.path("created").asInt()).isEqualTo(created);
        assertThat(body.path("skipped").asInt()).isEqualTo(skipped);
        assertThat(body.path("rejected").isArray()).isTrue();
        assertThat(body.path("rejected").size()).isEqualTo(rejected);
    }
    private void rejected(JsonNode report,int index,int row,String key,String reason) {
        var item=report.path("rejected").get(index);
        assertThat(item.path("row").asInt()).isEqualTo(row);
        assertThat(item.path("key").asText()).isEqualTo(key);
        assertThat(item.path("reason").asText()).isEqualTo(reason);
    }
    @Test void r2r4r6r10Windows1251SemicolonCreatesEightRejectsTwoAndRetryIsIdempotent() {
        StringBuilder data=new StringBuilder(STUDENTS.replace(',', ';'));
        for(int i=1;i<=10;i++) data.append(email("import"+i)).append(";Иванов Иван ").append(i).append(';').append(i==3||i==7?"XXX":facultyCode()).append(';').append(groupCode()).append("\r\n");
        byte[] bytes=data.toString().getBytes(Charset.forName("windows-1251"));
        var wire=adminWire();
        var result=wire.upload(IMPORT+"STUDENTS","students.csv",bytes,200);
        report(result,8,0,2);
        rejected(result,0,4,email("import3"),"факультет не найден");
        rejected(result,1,8,email("import7"),"факультет не найден");
        var users=items(get("/api/v1/admin/users?search="+prefix+"import"));
        assertThat(users).hasSize(8);
        for(var user:users) {
            assertThat(user.path("fullName").asText()).startsWith("Иванов Иван ");
            long id=user.path("id").asLong();
            assertThat(jdbc.queryForObject("select state from group_membership where student_id=?",String.class,id)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("select sum(amount) from ledger_entry where student_id=? and kind='INITIAL'",Integer.class,id)).isEqualTo(1000);
        }
        long before=jdbc.queryForObject("select count(*) from ledger_entry where kind='INITIAL' and student_id in (select id from app_user where email like ?)",Long.class,prefix+"%");
        report(wire.upload(IMPORT+"STUDENTS","students.csv",bytes,200),0,8,2);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where kind='INITIAL' and student_id in (select id from app_user where email like ?)",Long.class,prefix+"%")).isEqualTo(before);
    }
    @Test void r2r4QuotedCommaEscapedQuoteNewlineBomAndEmptyRecords() {
        String row="\""+email("quoted")+"\",\"Петрова, Мария \"\"М\"\"\nИвановна\","+facultyCode()+","+groupCode()+"\r\n";
        var result=csv("STUDENTS","\uFEFF"+STUDENTS+"\r\n"+row+row+email("extra")+",Имя,"+facultyCode()+","+groupCode()+",лишнее\r\n");
        report(result,1,1,1);
        assertThat(result.at("/rejected/0/reason").asText()).isEqualTo("неверное число колонок");
        assertThat(items(get("/api/v1/admin/users?search="+prefix+"quoted"))).singleElement().satisfies(u -> assertThat(u.path("fullName").asText()).isEqualTo("Петрова, Мария \"М\"\nИвановна"));
    }
    @Test void r5GroupsCreateSkipAndRejectConflictingCode() {
        String header="faculty_code,group_code,group_name,admission_year\r\n";
        String existing=facultyCode()+","+groupCode()+",Same,2026\r\n";
        var result=csv("GROUPS",header+facultyCode()+","+prefix+"new,Новая,2000\r\n"+existing+facultyCode()+","+groupCode()+",Другое,2026\r\n");
        report(result,1,1,1);
        rejected(result,0,4,groupCode(),"код группы занят");
    }
    @Test void r5YearsAreInclusiveAndInvalidRowsDoNotBlockValidRows() {
        var data=new StringBuilder("faculty_code,group_code,group_name,admission_year\n");
        for(String year:List.of("1999","2000","2100","2101","2026.5")) data.append(facultyCode()).append(',').append(prefix).append(year).append(",Group,").append(year).append('\n');
        var result=csv("GROUPS",data.toString());
        report(result,2,0,3);
        assertThat(result.path("rejected").valueStream().map(n -> n.path("reason").asText()).toList()).containsOnly("неверный год набора");
    }
    @Test void r4r6StudentConflictsAndFirstColumnErrorAreReportedInOrder() {
        student("existing",faculty,group);
        jdbc.update("insert into teacher_allowlist(email,department_id) values (?,?)",email("reserved"),department);
        String data=STUDENTS+email("existing")+",Другое имя,"+facultyCode()+","+groupCode()+"\n"
                +email("reserved")+",Учитель,"+facultyCode()+","+groupCode()+"\n"
                +"invalid-email,Имя,"+facultyCode()+","+groupCode()+"\n"
                +email("otherfaculty")+",Имя,"+facultyCode()+","+prefix+"g3\n"
                +email("missinggroup")+",Имя,"+facultyCode()+",absent\n"
                +",,absent,absent\n";
        var result=csv("STUDENTS",data); report(result,0,0,6);
        assertThat(result.path("rejected").valueStream().map(n -> n.path("reason").asText()).toList()).containsExactly("email занят","email из списка преподавателей","неверный email","группа другого факультета","группа не найдена","пустое значение email");
    }
    @Test void r7WithoutSmtpImportedStudentNeedsTemporaryPassword() {
        report(csv("STUDENTS",STUDENTS+email("imported")+",Импортированный,"+facultyCode()+","+groupCode()+"\n"),1,0,0);
        request(browser(),"POST","/api/v1/auth/login",Map.of("email",email("imported"),"password",PASSWORD),401);
        long id=items(get("/api/v1/admin/users?search="+prefix+"imported")).getFirst().path("id").asLong();
        String temporary=request(admin,"POST","/api/v1/users/"+id+"/temporary-password",null,200).path("password").asText();
        assertThat(request(browser(),"POST","/api/v1/auth/login",Map.of("email",email("imported"),"password",temporary),200).path("mustChangePassword").asBoolean()).isTrue();
    }
    @Test void r9r11AllowlistAppendSkipConflictAndDeleteUnregisteredOnly() {
        student("student",faculty,group);
        String header="email,department_code\n";
        var result=csv("TEACHER_ALLOWLIST",header+email("allowed")+","+prefix+"d1\n"+email("allowed").toUpperCase()+","+prefix+"d1\n"+email("student")+","+prefix+"d1\n"+email("admin")+","+prefix+"d1\n"+email("allowed")+","+prefix+"d2\n");
        report(result,1,1,3);
        assertThat(result.path("rejected").valueStream().map(n->n.path("reason").asText()).toList()).containsExactly("email занят студентом","email занят администратором","email уже в списке с другой кафедрой");
        var allowed=items(get(ALLOWLIST)).stream().filter(n->n.path("email").asText().equals(email("allowed"))).findFirst().orElseThrow();
        assertThat(allowed.path("registered").asBoolean()).isFalse();
        request(admin,"DELETE",ALLOWLIST+"?email="+email("allowed"),null,204);
        assertThat(items(get(ALLOWLIST)).stream().map(n->n.path("email").asText()).toList()).doesNotContain(email("allowed"));
        csv("TEACHER_ALLOWLIST",header+email("registered")+","+prefix+"d1\n");
        teacher("registered","Registered teacher");
        var failure=request(admin,"DELETE",ALLOWLIST+"?email="+email("registered"),null,409);
        assertThat(failure.path("detail").asText()).isEqualTo("Преподаватель уже зарегистрирован — удалить нельзя");
        assertThat(items(get(ALLOWLIST)).stream().filter(n->n.path("email").asText().equals(email("registered"))).findFirst().orElseThrow().path("registered").asBoolean()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"bad-header","oversize","too-many"})
    void r3WholeFileValidationCreatesNothing(String kind) {
        byte[] bytes;
        if(kind.equals("oversize")) bytes=new byte[5*1024*1024+1];
        else if(kind.equals("too-many")) {
            var data=new StringBuilder(STUDENTS);
            for(int i=0;i<5001;i++) data.append(email("many"+i)).append(",Имя,").append(facultyCode()).append(',').append(groupCode()).append('\n');
            bytes=data.toString().getBytes(StandardCharsets.UTF_8);
        } else bytes="mail,name,faculty_code,group_code\n".getBytes(StandardCharsets.UTF_8);
        var result=adminWire().upload(IMPORT+"STUDENTS","students.csv",bytes,400);
        assertThat(result.path("detail").asText()).isEqualTo(kind.equals("bad-header")?"Не удалось прочитать файл: проверьте формат и заголовок":"Файл слишком большой: до 5 МБ и 5000 строк");
        assertThat(jdbc.queryForObject("select count(*) from app_user where email like ?",Integer.class,prefix+"many%")).isZero();
    }
    @Test void r2XlsxUsesFirstSheetAndSameRowReport() throws Exception {
        byte[] bytes=xlsx(List.of(List.of("email","full_name","faculty_code","group_code"),List.of(email("xlsx"),"Иванов Иван",facultyCode(),groupCode()),List.of(email("badxlsx"),"Не создан","XXX",groupCode())));
        var result=adminWire().upload(IMPORT+"STUDENTS","students.xlsx",bytes,200);
        report(result,1,0,1); rejected(result,0,3,email("badxlsx"),"факультет не найден");
        assertThat(items(get("/api/v1/admin/users?search="+prefix+"xlsx"))).singleElement().satisfies(u->assertThat(u.path("fullName").asText()).isEqualTo("Иванов Иван"));
    }
    @Test void r12TeacherCannotImportOrDeleteAllowlist() {
        teacher("teacher","Teacher");
        var result=wire("teacher",PASSWORD).upload(IMPORT+"GROUPS","groups.csv","faculty_code,group_code,group_name,admission_year\n".getBytes(StandardCharsets.UTF_8),403);
        assertThat(result.path("code").asText()).isEqualTo("FORBIDDEN");
        problem(login("teacher"),"DELETE",ALLOWLIST+"?email="+email("missing"),null,403,"FORBIDDEN");
        problem(login("teacher"),"GET",ALLOWLIST,null,403,"FORBIDDEN");
    }
    @Test void r3ExactlyFiveThousandRecordsAndFiveMiBAreAccepted() {
        String header="faculty_code,group_code,group_name,admission_year\n";
        String row="absent,"+prefix+"missing,Group,2026\n";
        var fiveThousand=csv("GROUPS",header+row.repeat(5000));
        report(fiveThousand,0,0,5000);
        byte[] exact=new byte[5*1024*1024];
        byte[] initial=(header+row).getBytes(StandardCharsets.UTF_8);
        System.arraycopy(initial,0,exact,0,initial.length);
        java.util.Arrays.fill(exact,initial.length,exact.length,(byte)'\n');
        report(adminWire().upload(IMPORT+"GROUPS","exact.csv",exact,200),0,0,1);
    }
    @Test void r4SaveFailureRollsBackEveryAcceptedStudent() {
        jdbc.execute("create function u13_reject_import() returns trigger language plpgsql as $$ begin if NEW.email='"+email("reject")+"' then raise exception 'synthetic storage failure'; end if; return NEW; end $$");
        jdbc.execute("create trigger u13_reject_import before insert on app_user for each row execute function u13_reject_import()");
        try {
            String data=STUDENTS+email("rollback")+",Первый,"+facultyCode()+","+groupCode()+"\n"+email("reject")+",Второй,"+facultyCode()+","+groupCode()+"\n";
            var result=adminWire().uploadFailure(IMPORT+"STUDENTS","rollback.csv",data.getBytes(StandardCharsets.UTF_8));
            assertThat(result.path("detail").asText()).isEqualTo("Импорт не выполнен, попробуйте ещё раз");
            assertThat(jdbc.queryForObject("select count(*) from app_user where email in (?,?)",Integer.class,email("rollback"),email("reject"))).isZero();
        } finally {
            jdbc.execute("drop trigger if exists u13_reject_import on app_user");
            jdbc.execute("drop function if exists u13_reject_import()");
        }
    }
    private byte[] xlsx(List<List<String>> rows) throws Exception {
        var output=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(output)) {
            entry(zip,"[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
            entry(zip,"_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            entry(zip,"xl/workbook.xml","<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Students\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            entry(zip,"xl/_rels/workbook.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            var xml=new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            int row=0;
            for(var values:rows) { xml.append("<row r=\"").append(++row).append("\">"); int col=0; for(String value:values) xml.append("<c r=\"").append((char)('A'+col++)).append(row).append("\" t=\"inlineStr\"><is><t>").append(value.replace("&","&amp;").replace("<","&lt;")).append("</t></is></c>"); xml.append("</row>"); }
            entry(zip,"xl/worksheets/sheet1.xml",xml.append("</sheetData></worksheet>").toString());
        }
        return output.toByteArray();
    }
    private void entry(ZipOutputStream zip,String name,String xml) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(xml.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
}

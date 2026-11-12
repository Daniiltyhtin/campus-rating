package ru.campusrating.admin;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.campusrating.support.U13HttpTestSupport;
import static org.assertj.core.api.Assertions.assertThat;

class U13AdminSubmissionIT extends U13HttpTestSupport {
    private long point;
    private long assignment;
    private long studentId;
    @BeforeEach void assignmentFixture() {
        long teacher=teacher("teacher","Teacher"); approve(teacher,department);
        studentId=student("student",faculty,group);
        jdbc.update("update group_membership set state='ACTIVE',activated_at=? where student_id=?",Timestamp.from(clock.instant()),studentId);
        point=request(login("teacher"),"POST","/api/v1/checkpoints",checkpoint(group),201).path("id").asLong();
        assignment=jdbc.queryForObject("select id from assignment where checkpoint_id=? and student_id=?",Long.class,point,studentId);
    }
    @AfterEach void removeAssignment() {
        jdbc.update("delete from submission where assignment_id in (select id from assignment where checkpoint_id=?)",point);
        jdbc.update("delete from assignment where checkpoint_id=?",point);
    }
    private String path() { return "/api/v1/assignments/"+assignment; }
    @Test void r2r4AdminSeesEmptyAssignmentAndMissingAssignmentIs404() {
        var result=get(path());
        assertThat(result.at("/student/id").asLong()).isEqualTo(studentId);
        assertThat(result.at("/checkpoint/id").asLong()).isEqualTo(point);
        assertThat(result.path("submissions").isArray()).isTrue();
        assertThat(result.path("submissions").size()).isZero();
        problem(admin,"GET","/api/v1/assignments/"+ABSENT,null,404,"NOT_FOUND");
    }
    @Test void r3AdminCannotSubmitWithdrawAcceptOrReturnAndStateDoesNotChange() {
        var before=get(path());
        assertThat(adminWire().link(path()+"/submission","https://example.org/admin",403).path("code").asText()).isEqualTo("FORBIDDEN");
        problem(admin,"POST",path()+"/submission/withdrawal",null,403,"FORBIDDEN");
        problem(admin,"POST",path()+"/return",Map.of("comment","Must not be stored"),403,"FORBIDDEN");
        problem(admin,"POST","/api/v1/checkpoints/"+point+"/assignments/accept",Map.of("assignmentIds",List.of(assignment)),403,"FORBIDDEN");
        assertThat(get(path())).isEqualTo(before);
    }
    @Test void r2r3AdminReadsWithdrawnLinkAndNewFileButCannotReturnSubmittedWork() {
        var student=wire("student",PASSWORD);
        student.link(path()+"/submission","https://example.org/v1",200);
        clock.set(clock.instant().plusSeconds(1));
        request(login("student"),"POST",path()+"/submission/withdrawal",null,200);
        clock.set(clock.instant().plusSeconds(1));
        byte[] pdf="%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF".getBytes(StandardCharsets.UTF_8);
        student.upload(path()+"/submission","report.pdf",pdf,200);
        var result=get(path());
        assertThat(result.path("submissions").size()).isEqualTo(2);
        assertThat(result.at("/submissions/0/fileName").asText()).isEqualTo("report.pdf");
        assertThat(result.at("/submissions/1/url").asText()).isEqualTo("https://example.org/v1");
        assertThat(result.at("/submissions/1/withdrawnAt").isNull()).isFalse();
        long version=result.at("/submissions/0/id").asLong();
        assertThat(adminWire().download(path()+"/submissions/"+version+"/file","report.pdf")).isEqualTo(pdf);
        problem(admin,"POST",path()+"/return",Map.of("comment","Admin must not return"),403,"FORBIDDEN");
        assertThat(get(path())).isEqualTo(result);
    }
    @Test void r2AdminReadsAllReturnedVersionsWithCommentAndAuthor() {
        wire("student",PASSWORD).link(path()+"/submission","https://example.org/work",200);
        request(login("teacher"),"POST",path()+"/return",Map.of("comment","Добавьте выводы"),200);
        var result=get(path());
        assertThat(result.at("/submissions/0/returnComment").asText()).isEqualTo("Добавьте выводы");
        assertThat(result.at("/submissions/0/returnedAt").isNull()).isFalse();
        assertThat(result.at("/submissions/0/url").asText()).isEqualTo("https://example.org/work");
    }
}

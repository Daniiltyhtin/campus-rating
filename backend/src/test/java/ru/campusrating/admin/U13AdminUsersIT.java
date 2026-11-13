package ru.campusrating.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.campusrating.support.U13HttpTestSupport;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

class U13AdminUsersIT extends U13HttpTestSupport {
    private static final String USERS = "/api/v1/admin/users";
    private String reset(long id) { return "/api/v1/users/" + id + "/temporary-password"; }
    private JsonNode row(long id) {
        return items(get(USERS + "?search=" + prefix)).stream().filter(n -> n.path("id").asLong() == id).findFirst().orElseThrow();
    }
    @Test void r1r2SearchIsTrimmedCaseInsensitiveAndFiltersRole() {
        long student = student("alice",faculty,group);
        long teacher = teacher("teacher","Alice teacher");
        assertThat(ids(get(USERS + "?search=" + ("  " + email("ALICE") + "  ")))).containsExactly(student);
        assertThat(ids(get(USERS + "?search=" + prefix + "&role=TEACHER"))).containsExactly(teacher);
        assertThat(ids(get(USERS + "?search=" + prefix + "&role=STUDENT"))).containsExactly(student);
        assertThat(items(get(USERS + "?search=zzzz-nobody-" + prefix))).isEmpty();
        assertThat(get(USERS + "?search=x")).isEqualTo(get(USERS));
        var actual = row(student);
        assertThat(actual.path("email").asText()).isEqualTo(email("alice"));
        assertThat(actual.path("role").asText()).isEqualTo("STUDENT");
        assertThat(actual.at("/group/id").asLong()).isEqualTo(group);
        assertThat(actual.path("passwordSetAt").isNull()).isTrue();
        assertThat(row(teacher).at("/department/id").asLong()).isEqualTo(department);
    }
    @Test void r3CursorReturnsFiftyFiftyTwentyWithoutDuplicatesAndBreaksNameTiesById() {
        var expected = new ArrayList<Long>();
        for (int i=0;i<120;i++) expected.add(jdbc.queryForObject("insert into app_user(email,role,full_name) values (?,'STUDENT',?) returning id",Long.class,email("page" + i),prefix + " Page " + String.format("%03d",i/2)));
        var all = new ArrayList<Long>();
        String cursor = null;
        for (int size : List.of(50,50,20)) {
            var page = get(USERS + "?search=" + (prefix + " Page") + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(items(page)).hasSize(size);
            all.addAll(ids(page));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
            if (size==50) assertThat(cursor).isNotBlank();
        }
        assertThat(cursor).isNull();
        assertThat(all).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    }
    @Test void r5GroupIncludesActiveAndPendingButNotOtherGroups() {
        long active = student("active",faculty,group);
        long pending = student("pending",faculty,group);
        jdbc.update("update group_membership set state='PENDING',activated_at=null where student_id=?",pending);
        long elsewhere = student("elsewhere",otherFaculty,otherGroup);
        jdbc.update("update group_membership set state='ACTIVE',activated_at=? where student_id=?",java.sql.Timestamp.from(clock.instant()),active);
        long teacher = teacher("teacher","Teacher"); approve(teacher,department);
        var session=login("teacher");
        var result=get(session,"/api/v1/groups/"+group+"/students");
        assertThat(ids(result)).containsExactly(active,pending).doesNotContain(elsewhere);
        assertThat(items(result).stream().map(n -> n.path("membershipState").asText()).toList()).containsExactly("ACTIVE","PENDING");
        problem(session,"GET","/api/v1/groups/"+otherGroup+"/students",null,403,"FORBIDDEN");
    }
    @Test void r7r8r9ResetExpiresAllSessionsForcesDifferentPasswordAndAuditsOnlySelf() {
        long target=student("target",faculty,group);
        var first=login("target"); var second=login("target");
        request(first,"GET","/api/v1/me/password-audit",null,204);
        String temporary=request(admin,"POST",reset(target),null,200).path("password").asText();
        assertThat(temporary).matches("[A-HJ-NP-Za-km-z2-9]{12}");
        problem(first,"GET","/api/v1/auth/me",null,401,"UNAUTHENTICATED");
        problem(second,"GET","/api/v1/auth/me",null,401,"UNAUTHENTICATED");
        var restored=browser();
        var signedIn=request(restored,"POST","/api/v1/auth/login",Map.of("email",email("target"),"password",temporary),200);
        assertThat(signedIn.path("mustChangePassword").asBoolean()).isTrue();
        problem(restored,"GET","/api/v1/me/assignments",null,403,"PASSWORD_CHANGE_REQUIRED");
        request(restored,"POST","/api/v1/auth/password",Map.of("currentPassword",temporary,"newPassword",temporary),400);
        request(restored,"POST","/api/v1/auth/password",Map.of("currentPassword",temporary,"newPassword","New-synthetic-password-57"),204);
        var audit=get(restored,"/api/v1/me/password-audit");
        assertThat(audit.at("/actor/fullName").asText()).isEqualTo("admin");
        assertThat(java.time.Instant.parse(audit.path("createdAt").asText())).isEqualTo(clock.instant());
        assertThat(row(target).path("passwordSetAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(get(USERS+"?search="+prefix).toString()).doesNotContain(temporary);
        assertThat(get(restored,"/api/v1/auth/me").toString()).doesNotContain(temporary);
        assertThat(get(admin,"/api/v1/users/"+target+"/profile").toString()).doesNotContain("passwordSetAt","passwordAudit",temporary);
    }
    @Test void r7RepeatedResetsGenerateAllowedPasswordsAndKeepLatestAudit() {
        long target=student("target",faculty,group);
        var generated=new java.util.HashSet<String>();
        for (int i=0;i<100;i++) {
            String password=request(admin,"POST",reset(target),null,200).path("password").asText();
            assertThat(password).matches("[A-HJ-NP-Za-km-z2-9]{12}");
            generated.add(password);
        }
        assertThat(generated).hasSize(100);
        assertThat(row(target).path("passwordSetAt").isNull()).isFalse();
    }
    @Test void r10TeacherOnlyResetsStudentsInAccessibleGroups() {
        long target=student("target",faculty,group);
        long foreign=student("foreign",otherFaculty,otherGroup);
        long teacher=teacher("teacher","Teacher"); approve(teacher,department);
        var session=login("teacher");
        assertThat(request(session,"POST",reset(target),null,200).path("password").asText()).hasSize(12);
        problem(session,"POST",reset(foreign),null,403,"FORBIDDEN");
        problem(session,"POST",reset(teacher),null,403,"FORBIDDEN");
        var before=row(foreign);
        assertThat(before.path("passwordSetAt").isNull()).isTrue();
        get(login("foreign"),"/api/v1/auth/me");
    }
    @Test void r10AdminMayResetTeacherButNeverAdminAndStudentCannotResetAnyone() {
        long teacher=teacher("teacher","Teacher");
        assertThat(request(admin,"POST",reset(teacher),null,200).path("password").asText()).hasSize(12);
        long adminId=get(admin,"/api/v1/auth/me").path("id").asLong();
        problem(admin,"POST",reset(adminId),null,403,"FORBIDDEN");
        student("student",faculty,group); var session=login("student");
        problem(session,"POST",reset(teacher),null,403,"FORBIDDEN");
        problem(session,"GET",USERS,null,403,"FORBIDDEN");
        problem(session,"GET","/api/v1/groups/"+group+"/students",null,403,"FORBIDDEN");
    }
}

package ru.campusrating.identity;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TeacherScopeIT extends IdentityAcceptanceSupport {
    @Autowired private TeacherScope scope;

    @Test
    void selectedDepartmentAndAllowlistedEmailDoNotApproveTeacherInOffMode() {
        jdbc.update("insert into teacher_allowlist(email,department_id) values (?,?)", email("teacher"), department);
        var browser = browser();
        long id = registerTeacher(browser, "teacher");
        login(browser, email("teacher"));
        assertThat(browser.get("/api/v1/auth/me").body().path("teacherApproved").asBoolean()).isFalse();
        assertThat(scope.accessibleGroupIds(id)).isEmpty();
        assertThat(scope.isAccessible(id, group)).isFalse();
        assertThatThrownBy(() -> scope.requireAccessible(id, List.of(group))).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ProblemCode.GROUP_NOT_ACCESSIBLE));
    }

    @Test
    void approvedTeacherGetsFacultyAndExplicitGroupOrFacultyGrants() {
        var browser = browser();
        long teacher = registerTeacher(browser, "teacher");
        login(browser, email("teacher"));
        jdbc.update("insert into teacher_approval(teacher_id) values (?)", teacher);
        assertThat(browser.get("/api/v1/auth/me").body().path("teacherApproved").asBoolean()).isTrue();
        assertThat(scope.accessibleGroupIds(teacher)).contains(group).doesNotContain(otherGroup);
        jdbc.update("insert into teacher_grant(teacher_id,kind,group_id) values (?,'GROUP',?)", teacher, otherGroup);
        assertThat(scope.accessibleGroupIds(teacher)).contains(group, otherGroup);
        jdbc.update("delete from teacher_grant where teacher_id=?", teacher);
        jdbc.update("insert into teacher_grant(teacher_id,kind,faculty_id) values (?,'FACULTY',?)", teacher, otherFaculty);
        assertThat(scope.accessibleGroupIds(teacher)).contains(group, otherGroup);
        jdbc.update("delete from teacher_approval where teacher_id=?", teacher);
        assertThat(scope.accessibleGroupIds(teacher)).isEmpty();
        assertThat(browser.get("/api/v1/auth/me").body().path("teacherApproved").asBoolean()).isFalse();
    }

    @Test
    void courseGrantsFollowMoscowSeptemberBoundaryAndExcludeInvalidCourses() {
        long teacher = registerTeacher(browser(), "teacher");
        jdbc.update("insert into teacher_approval(teacher_id) values (?)", teacher);
        long previous = group(otherFaculty, 2025, "previous");
        long future = group(otherFaculty, 2027, "future");
        long seventh = group(otherFaculty, 2020, "seventh");
        long sixth = group(otherFaculty, 2021, "sixth");
        jdbc.update("insert into teacher_grant(teacher_id,kind,faculty_id,course) values (?,'FACULTY_COURSE',?,1)", teacher, otherFaculty);
        clock.set(Instant.parse("2026-08-31T20:59:59Z"));
        assertThat(scope.accessibleGroupIds(teacher)).contains(previous).doesNotContain(otherGroup, future, seventh);
        clock.set(Instant.parse("2026-08-31T21:00:00Z"));
        assertThat(scope.accessibleGroupIds(teacher)).contains(otherGroup).doesNotContain(previous, future, seventh);
        jdbc.update("delete from teacher_grant where teacher_id=?", teacher);
        jdbc.update("insert into teacher_grant(teacher_id,kind,course) values (?,'COURSE',1)", teacher);
        assertThat(scope.accessibleGroupIds(teacher)).contains(group, otherGroup).doesNotContain(previous, future, seventh);
        jdbc.update("update teacher_grant set course=6 where teacher_id=?", teacher);
        assertThat(scope.accessibleGroupIds(teacher)).contains(group, sixth).doesNotContain(otherGroup, previous, future, seventh);
    }

    @Test
    void databaseRejectsImpossibleGrantTargets() {
        long teacher = registerTeacher(browser(), "teacher");
        assertThatThrownBy(() -> jdbc.update("insert into teacher_grant(teacher_id,kind) values (?,'GROUP')", teacher))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into teacher_grant(teacher_id,kind,faculty_id,course) values (?,'COURSE',?,1)", teacher, faculty))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into teacher_grant(teacher_id,kind,course) values (?,'COURSE',7)", teacher))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

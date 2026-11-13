package ru.campusrating.identity;

import ru.campusrating.support.UserNameFixture;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentitySeamsIT extends IdentityAcceptanceSupport {
    @Autowired private GroupMembers members;
    @Autowired private CurrentUser currentUser;

    @Test
    void groupMembersDistinguishesPendingAndActiveAndReturnsCurrentLabels() {
        long active = registerStudent(browser(), "active");
        long pending = registerStudent(browser(), "pending");
        long teacher = registerTeacher(browser(), "teacher");
        jdbc.update("insert into teacher_approval(teacher_id, approved_at) values (?, now())", teacher);
        var teacherSession = browser();
        login(teacherSession, email("teacher"));
        assertThat(teacherSession.post("/api/v1/memberships/" + active + "/approval", java.util.Map.of()).status()).isEqualTo(204);
        assertThat(members.activeStudents(List.of(group))).extracting(StudentMember::studentId).containsExactly(active);
        assertThat(members.membership(pending)).hasValueSatisfying(member -> {
            assertThat(member.state().name()).isEqualTo("PENDING");
            assertThat(member.activatedAt()).isNull();
        });
        assertThat(members.membership(teacher)).isEmpty();
        assertThat(members.memberships(List.of(active, pending, teacher))).containsOnlyKeys(active, pending);
        jdbc.update("update app_user set last_name=?,first_name=?,middle_name=? where id=?",
                UserNameFixture.last("Новое имя"),
                UserNameFixture.first("Новое имя"),
                UserNameFixture.middle("Новое имя"),
                active);
        assertThat(members.users(List.of(active, teacher))).containsOnlyKeys(active, teacher);
        assertThat(members.users(List.of(active)).get(active).fullName()).isEqualTo("Новое имя");
        assertThat(members.activeStudents(List.of(group))).extracting(StudentMember::fullName).containsExactly("Новое имя");
        assertThat(members.groups(List.of(group)).get(group).facultyId()).isEqualTo(faculty);
        assertThat(members.card(active)).hasValueSatisfying(card -> {
            assertThat(card.role().name()).isEqualTo("STUDENT");
            assertThat(card.groupId()).isEqualTo(group);
            assertThat(card.departmentId()).isNull();
        });
        assertThat(members.card(teacher)).hasValueSatisfying(card -> {
            assertThat(card.role().name()).isEqualTo("TEACHER");
            assertThat(card.departmentId()).isEqualTo(department);
            assertThat(card.groupId()).isNull();
        });
        assertThat(members.activeStudents(List.of())).isEmpty();
        assertThat(members.users(List.of())).isEmpty();
    }

    @Test
    void currentUserOutsideAuthenticatedRequestDoesNotInventIdentity() {
        assertThat(currentUser.find()).isEmpty();
        assertThatThrownBy(currentUser::require).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ProblemCode.UNAUTHENTICATED));
    }
}

package ru.campusrating.profile.domain;

import org.junit.jupiter.api.Test;
import ru.campusrating.identity.Role;

import static org.assertj.core.api.Assertions.assertThat;

class TaskVisibilityTest {
    @Test
    void ownerSeesOwnViewWhateverTheRole() {
        assertThat(TaskVisibility.of(7, Role.STUDENT, 7)).isEqualTo(TaskVisibility.OWN);
    }

    @Test
    void adminSeesAdminViewOfSomeoneElse() {
        assertThat(TaskVisibility.of(1, Role.ADMIN, 7)).isEqualTo(TaskVisibility.ADMIN);
    }

    @Test
    void otherStudentsAndTeachersSeePublicView() {
        assertThat(TaskVisibility.of(8, Role.STUDENT, 7)).isEqualTo(TaskVisibility.PUBLIC);
        assertThat(TaskVisibility.of(2, Role.TEACHER, 7)).isEqualTo(TaskVisibility.PUBLIC);
    }

    @Test
    void onlyPublicHidesAssignment() {
        assertThat(TaskVisibility.PUBLIC.revealsAssignment()).isFalse();
        assertThat(TaskVisibility.OWN.revealsAssignment()).isTrue();
        assertThat(TaskVisibility.ADMIN.revealsAssignment()).isTrue();
    }
}

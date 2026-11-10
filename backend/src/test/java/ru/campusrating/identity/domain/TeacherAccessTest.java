package ru.campusrating.identity.domain;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.campusrating.directory.FacultyInfo;
import ru.campusrating.directory.GroupInfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TeacherAccessTest {
    private final List<GroupInfo> groups = List.of(group(1, 1, 2026), group(2, 2, 2025), group(3, 2, 2026),
            group(4, 2, 2027), group(5, 2, 2020));

    @Test
    void approvalAndExplicitTargetsControlTheUnion() {
        assertThat(TeacherAccess.groups(false, 1, groups, List.of(new TeacherGrant.Faculty(2)), LocalDate.of(2026, 11, 9))).isEmpty();
        assertThat(TeacherAccess.groups(true, 1, groups, List.of(new TeacherGrant.Group(2)), LocalDate.of(2026, 11, 9)))
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(TeacherAccess.groups(true, 1, groups, List.of(new TeacherGrant.Faculty(2)), LocalDate.of(2026, 11, 9)))
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void courseTargetsMoveOnSeptemberFirstAndExcludeFutureAndSeventhCourse() {
        var facultyCourse = List.<TeacherGrant>of(new TeacherGrant.FacultyCourse(2, 1));
        assertThat(TeacherAccess.groups(true, 1, groups, facultyCourse, LocalDate.of(2026, 8, 31))).containsExactlyInAnyOrder(1L, 2L);
        assertThat(TeacherAccess.groups(true, 1, groups, facultyCourse, LocalDate.of(2026, 9, 1))).containsExactlyInAnyOrder(1L, 3L);
        assertThat(TeacherAccess.groups(true, 1, groups, List.of(new TeacherGrant.Course(1)), LocalDate.of(2026, 9, 1)))
                .containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void grantConstructionRejectsInvalidIdentifiersAndCourses() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TeacherGrant.Group(0));
        assertThatIllegalArgumentException().isThrownBy(() -> new TeacherGrant.Faculty(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> new TeacherGrant.Course(7));
        assertThatIllegalArgumentException().isThrownBy(() -> new TeacherGrant.FacultyCourse(1, 0));
    }

    private GroupInfo group(long id, long faculty, int year) {
        return new GroupInfo(id, "Группа", new FacultyInfo(faculty, "Ф", "Факультет"), year);
    }
}

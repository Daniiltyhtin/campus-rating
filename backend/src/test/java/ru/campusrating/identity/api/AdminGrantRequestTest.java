package ru.campusrating.identity.api;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.campusrating.identity.domain.TeacherGrant;
import ru.campusrating.shared.error.ValidationFailedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminGrantRequestTest {
    @Test
    void parsesExactlyTheFourGrantShapes() {
        assertThat(AdminTeachersController.parseGrant(Map.of("kind", "GROUP", "groupId", 11))).isEqualTo(new TeacherGrant.Group(11));
        assertThat(AdminTeachersController.parseGrant(Map.of("kind", "FACULTY", "facultyId", 2))).isEqualTo(new TeacherGrant.Faculty(2));
        assertThat(AdminTeachersController.parseGrant(Map.of("kind", "FACULTY_COURSE", "facultyId", 2, "course", 6)))
                .isEqualTo(new TeacherGrant.FacultyCourse(2, 6));
        assertThat(AdminTeachersController.parseGrant(Map.of("kind", "COURSE", "course", 1))).isEqualTo(new TeacherGrant.Course(1));
    }

    @Test
    void rejectsEvenNullExtraFields() {
        var body = new HashMap<String, Object>(Map.of("kind", "GROUP", "groupId", 11));
        body.put("course", null);
        invalid(body, "course");
        body.remove("course");
        body.put("unexpected", 1);
        invalid(body, "unexpected");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 7, -1, 2147483647})
    void rejectsCoursesOutsideRange(int course) {
        invalid(Map.of("kind", "COURSE", "course", course), "course");
    }

    @Test
    void rejectsMissingNullFractionalAndOverflowNumbers() {
        invalid(Map.of("kind", "GROUP"), "groupId");
        var body = new HashMap<String, Object>(Map.of("kind", "GROUP"));
        body.put("groupId", null);
        invalid(body, "groupId");
        for (Object value : new Object[] {0, -1, "11", 1.5, new java.math.BigInteger("9223372036854775808")}) {
            invalid(Map.of("kind", "GROUP", "groupId", value), "groupId");
        }
        invalid(Map.of("kind", "UNKNOWN"), "kind");
        invalid(Map.of(), "kind");
    }

    private void invalid(Map<String, Object> body, String field) {
        assertThatThrownBy(() -> AdminTeachersController.parseGrant(body)).isInstanceOfSatisfying(
                ValidationFailedException.class, exception -> assertThat(exception.fieldErrors()).anyMatch(error -> error.field().equals(field)));
    }
}

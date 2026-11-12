package ru.campusrating.directory.api;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import ru.campusrating.directory.api.AdminDirectoryController.DepartmentWrite;
import ru.campusrating.directory.api.AdminDirectoryController.FacultyWrite;
import ru.campusrating.directory.api.AdminDirectoryController.StudyGroupWrite;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminDirectoryRequestTest {
    @Test
    void trimsBeforeValidatingLengthsAndKeepsCase() {
        var body = new FacultyWrite("  " + "A".repeat(64) + "  ", "  Name  ");
        assertThat(body.code()).isEqualTo("A".repeat(64));
        assertThat(body.name()).isEqualTo("Name");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(body)).isEmpty();
            assertThat(factory.getValidator().validate(new FacultyWrite("A".repeat(65), "Name")))
                    .anyMatch(violation -> violation.getPropertyPath().toString().equals("code"));
        }
    }

    @Test
    void validatesMissingAndBlankFieldsAndAdmissionYearBounds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new DepartmentWrite("  ", null, null)))
                    .extracting(violation -> violation.getPropertyPath().toString()).contains("code", "name", "facultyId");
            for (Integer year : new Integer[] {null, 0, 10000}) {
                assertThat(validator.validate(new StudyGroupWrite("G", "Group", 1L, year)))
                        .anyMatch(violation -> violation.getPropertyPath().toString().equals("admissionYear"));
            }
            for (int year : new int[] {1, 9999}) {
                assertThat(validator.validate(new StudyGroupWrite("G", "Group", 1L, year))).isEmpty();
            }
        }
    }

    @Test
    void rejectsUnknownFieldsEvenWithLenientApplicationMapper() {
        var json = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        assertThatThrownBy(() -> json.readValue("{\"code\":\"F\",\"name\":\"Faculty\",\"extra\":null}", FacultyWrite.class))
                .hasMessageContaining("extra");
        assertThatThrownBy(() -> json.readValue("{\"code\":\"G\",\"name\":\"Group\",\"facultyId\":1,\"admissionYear\":2026,\"extra\":1}", StudyGroupWrite.class))
                .hasMessageContaining("extra");
        assertThatThrownBy(() -> json.readValue("{\"code\":\"D\",\"name\":\"Department\",\"facultyId\":1,\"extra\":1}", DepartmentWrite.class))
                .hasMessageContaining("extra");
    }
}

package ru.campusrating.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.directory.DepartmentInfo;
import ru.campusrating.directory.FacultyInfo;
import ru.campusrating.identity.api.AuthDtos.StudyGroupList;
import ru.campusrating.identity.api.AuthDtos.StudyGroupRef;
import ru.campusrating.identity.domain.TeacherGrant;
import ru.campusrating.identity.internal.AdminTeacherService;
import ru.campusrating.shared.error.FieldError;
import ru.campusrating.shared.error.ValidationFailedException;

@RestController
@RequestMapping("/api/v1/admin/teachers")
public class AdminTeachersController {
    private final AdminTeacherService service;

    public AdminTeachersController(AdminTeacherService service) { this.service = service; }

    @GetMapping
    public AdminTeacherList listAdminTeachers() { return new AdminTeacherList(service.list()); }

    @GetMapping("/{teacherId}")
    public AdminTeacher getAdminTeacher(@PathVariable @Min(1) long teacherId) { return service.get(teacherId); }

    @PostMapping("/{teacherId}/approval")
    public AdminTeacher approveAdminTeacher(@PathVariable @Min(1) long teacherId, @Valid @RequestBody TeacherApprovalRequest request) {
        return service.approve(teacherId, request.departmentId());
    }

    @DeleteMapping("/{teacherId}/application")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rejectAdminTeacher(@PathVariable @Min(1) long teacherId) { service.reject(teacherId); }

    @GetMapping("/{teacherId}/grants")
    public AdminTeacherGrantList listAdminTeacherGrants(@PathVariable @Min(1) long teacherId) {
        return new AdminTeacherGrantList(service.grants(teacherId));
    }

    @PostMapping("/{teacherId}/grants")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AdminTeacherGrant.class)))
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = AdminTeacherGrant.class)))
    public ResponseEntity<AdminTeacherGrant> createAdminTeacherGrant(@PathVariable @Min(1) long teacherId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = TeacherGrantCreate.class)))
            @RequestBody Map<String, Object> request) {
        var result = service.createGrant(teacherId, parseGrant(request));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.grant());
    }

    @DeleteMapping("/{teacherId}/grants/{grantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAdminTeacherGrant(@PathVariable @Min(1) long teacherId, @PathVariable @Min(1) long grantId) {
        service.deleteGrant(teacherId, grantId);
    }

    @GetMapping("/{teacherId}/accessible-groups")
    public StudyGroupList listAdminTeacherAccessibleGroups(@PathVariable @Min(1) long teacherId) {
        return new StudyGroupList(service.accessibleGroups(teacherId));
    }

    static TeacherGrant parseGrant(Map<String, Object> request) {
        Set<String> fields = switch (request.get("kind")) {
            case String kind when kind.equals("GROUP") -> Set.of("kind", "groupId");
            case String kind when kind.equals("FACULTY") -> Set.of("kind", "facultyId");
            case String kind when kind.equals("FACULTY_COURSE") -> Set.of("kind", "facultyId", "course");
            case String kind when kind.equals("COURSE") -> Set.of("kind", "course");
            case null, default -> throw invalid("kind");
        };
        for (String field : request.keySet()) if (!fields.contains(field)) throw invalid(field);
        return switch ((String) request.get("kind")) {
            case "GROUP" -> new TeacherGrant.Group(integer(request, "groupId", Long.MAX_VALUE));
            case "FACULTY" -> new TeacherGrant.Faculty(integer(request, "facultyId", Long.MAX_VALUE));
            case "FACULTY_COURSE" -> new TeacherGrant.FacultyCourse(integer(request, "facultyId", Long.MAX_VALUE), (int) integer(request, "course", 6));
            case "COURSE" -> new TeacherGrant.Course((int) integer(request, "course", 6));
            default -> throw invalid("kind");
        };
    }

    private static long integer(Map<String, Object> request, String field, long maximum) {
        Object value = request.get(field);
        BigInteger number = switch (value) {
            case Integer integer -> BigInteger.valueOf(integer);
            case Long integer -> BigInteger.valueOf(integer);
            case BigInteger integer -> integer;
            case null, default -> throw invalid(field);
        };
        if (number.signum() <= 0 || number.compareTo(BigInteger.valueOf(maximum)) > 0) throw invalid(field);
        return number.longValueExact();
    }

    private static ValidationFailedException invalid(String field) {
        return new ValidationFailedException("Проверьте поля выдачи", List.of(new FieldError(field, "INVALID", "Неверное поле выдачи")));
    }

    @Schema(requiredProperties = {"id", "fullName", "email", "department", "teacherApproved"})
    public record AdminTeacher(@Min(1) long id, String fullName, @Schema(format = "email") String email,
                               DepartmentInfo department, boolean teacherApproved) {}
    @Schema(requiredProperties = "items")
    public record AdminTeacherList(List<AdminTeacher> items) {}
    @Schema(requiredProperties = "departmentId", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record TeacherApprovalRequest(@NotNull @Min(1) Long departmentId) {
        @JsonAnySetter public void rejectUnknown(String field, Object value) { throw invalid(field); }
    }

    @Schema(oneOf = {GroupRequest.class, FacultyRequest.class, FacultyCourseRequest.class, CourseRequest.class},
            discriminatorProperty = "kind", discriminatorMapping = {
                @DiscriminatorMapping(value = "GROUP", schema = GroupRequest.class),
                @DiscriminatorMapping(value = "FACULTY", schema = FacultyRequest.class),
                @DiscriminatorMapping(value = "FACULTY_COURSE", schema = FacultyCourseRequest.class),
                @DiscriminatorMapping(value = "COURSE", schema = CourseRequest.class)})
    public sealed interface TeacherGrantCreate permits GroupRequest, FacultyRequest, FacultyCourseRequest, CourseRequest {}

    @Schema(name = "TeacherGrantGROUPRequest", requiredProperties = {"kind", "groupId"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record GroupRequest(@Schema(_const = "GROUP") String kind, @Min(1) long groupId) implements TeacherGrantCreate {}
    @Schema(name = "TeacherGrantFACULTYRequest", requiredProperties = {"kind", "facultyId"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record FacultyRequest(@Schema(_const = "FACULTY") String kind, @Min(1) long facultyId) implements TeacherGrantCreate {}
    @Schema(name = "TeacherGrantFACULTY_COURSERequest", requiredProperties = {"kind", "facultyId", "course"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record FacultyCourseRequest(@Schema(_const = "FACULTY_COURSE") String kind, @Min(1) long facultyId,
                                       @Min(1) @Max(6) int course) implements TeacherGrantCreate {}
    @Schema(name = "TeacherGrantCOURSERequest", requiredProperties = {"kind", "course"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CourseRequest(@Schema(_const = "COURSE") String kind, @Min(1) @Max(6) int course) implements TeacherGrantCreate {}

    @Schema(oneOf = {GroupGrant.class, FacultyGrant.class, FacultyCourseGrant.class, CourseGrant.class},
            discriminatorProperty = "kind", discriminatorMapping = {
                @DiscriminatorMapping(value = "GROUP", schema = GroupGrant.class),
                @DiscriminatorMapping(value = "FACULTY", schema = FacultyGrant.class),
                @DiscriminatorMapping(value = "FACULTY_COURSE", schema = FacultyCourseGrant.class),
                @DiscriminatorMapping(value = "COURSE", schema = CourseGrant.class)})
    public sealed interface AdminTeacherGrant permits GroupGrant, FacultyGrant, FacultyCourseGrant, CourseGrant {}
    @Schema(name = "TeacherGrantGROUPView", requiredProperties = {"id", "kind", "group"})
    public record GroupGrant(@Min(1) long id, StudyGroupRef group) implements AdminTeacherGrant {
        @JsonProperty("kind") @Schema(_const = "GROUP") public String kind() { return "GROUP"; }
    }
    @Schema(name = "TeacherGrantFACULTYView", requiredProperties = {"id", "kind", "faculty"})
    public record FacultyGrant(@Min(1) long id, FacultyInfo faculty) implements AdminTeacherGrant {
        @JsonProperty("kind") @Schema(_const = "FACULTY") public String kind() { return "FACULTY"; }
    }
    @Schema(name = "TeacherGrantFACULTY_COURSEView", requiredProperties = {"id", "kind", "faculty", "course"})
    public record FacultyCourseGrant(@Min(1) long id, FacultyInfo faculty, @Min(1) @Max(6) int course) implements AdminTeacherGrant {
        @JsonProperty("kind") @Schema(_const = "FACULTY_COURSE") public String kind() { return "FACULTY_COURSE"; }
    }
    @Schema(name = "TeacherGrantCOURSEView", requiredProperties = {"id", "kind", "course"})
    public record CourseGrant(@Min(1) long id, @Min(1) @Max(6) int course) implements AdminTeacherGrant {
        @JsonProperty("kind") @Schema(_const = "COURSE") public String kind() { return "COURSE"; }
    }
    @Schema(requiredProperties = "items")
    public record AdminTeacherGrantList(List<AdminTeacherGrant> items) {}
}

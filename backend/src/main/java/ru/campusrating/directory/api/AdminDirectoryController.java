package ru.campusrating.directory.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.directory.DepartmentInfo;
import ru.campusrating.directory.FacultyInfo;
import ru.campusrating.directory.internal.AdminDirectoryService;

@RestController
@RequestMapping("/api/v1/admin/directory")
public class AdminDirectoryController {
    private final AdminDirectoryService service;
    public AdminDirectoryController(AdminDirectoryService service) { this.service = service; }

    @GetMapping("/faculties")
    public Items<FacultyInfo> listAdminFaculties() { return new Items<>(service.faculties()); }
    @PostMapping("/faculties")
    @ResponseStatus(HttpStatus.CREATED)
    public FacultyInfo createAdminFaculty(@Valid @RequestBody FacultyWrite request) { return service.writeFaculty(null, request); }
    @PutMapping("/faculties/{id}")
    public FacultyInfo updateAdminFaculty(@PathVariable @Min(1) long id, @Valid @RequestBody FacultyWrite request) {
        return service.writeFaculty(id, request);
    }
    @DeleteMapping("/faculties/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAdminFaculty(@PathVariable @Min(1) long id) { service.delete(AdminDirectoryService.Kind.FACULTY, id); }

    @GetMapping("/groups")
    public Items<AdminStudyGroup> listAdminGroups() { return new Items<>(service.groups()); }
    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminStudyGroup createAdminStudyGroup(@Valid @RequestBody StudyGroupWrite request) { return service.writeStudyGroup(null, request); }
    @PutMapping("/groups/{id}")
    public AdminStudyGroup updateAdminStudyGroup(@PathVariable @Min(1) long id, @Valid @RequestBody StudyGroupWrite request) {
        return service.writeStudyGroup(id, request);
    }
    @DeleteMapping("/groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAdminStudyGroup(@PathVariable @Min(1) long id) { service.delete(AdminDirectoryService.Kind.STUDY_GROUP, id); }

    @GetMapping("/departments")
    public Items<DepartmentInfo> listAdminDepartments() { return new Items<>(service.departments()); }
    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    public DepartmentInfo createAdminDepartment(@Valid @RequestBody DepartmentWrite request) { return service.writeDepartment(null, request); }
    @PutMapping("/departments/{id}")
    public DepartmentInfo updateAdminDepartment(@PathVariable @Min(1) long id, @Valid @RequestBody DepartmentWrite request) {
        return service.writeDepartment(id, request);
    }
    @DeleteMapping("/departments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAdminDepartment(@PathVariable @Min(1) long id) { service.delete(AdminDirectoryService.Kind.DEPARTMENT, id); }

    public interface StrictWrite {
        @JsonAnySetter
        default void rejectUnknown(String field, Object value) { throw new IllegalArgumentException("Неизвестное поле: " + field); }
    }

    @Schema(requiredProperties = "items")
    public record Items<T>(List<T> items) {}
    @Schema(requiredProperties = {"id", "code", "name", "faculty", "admissionYear"})
    public record AdminStudyGroup(@Min(1) long id, String code, String name, FacultyInfo faculty,
                                  @Min(1) @Max(9999) int admissionYear) {}
    @Schema(requiredProperties = {"code", "name"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record FacultyWrite(@NotBlank @Size(max = 64) @Schema(pattern = "\\S") String code,
            @NotBlank @Size(max = 255) @Schema(pattern = "\\S") String name) implements StrictWrite {
        public FacultyWrite {
            code = code == null ? null : code.trim();
            name = name == null ? null : name.trim();
        }
    }
    @Schema(requiredProperties = {"code", "name", "facultyId", "admissionYear"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record StudyGroupWrite(@NotBlank @Size(max = 64) @Schema(pattern = "\\S") String code,
            @NotBlank @Size(max = 255) @Schema(pattern = "\\S") String name, @NotNull @Min(1) Long facultyId, @NotNull @Min(1) @Max(9999) Integer admissionYear) implements StrictWrite {
        public StudyGroupWrite {
            code = code == null ? null : code.trim();
            name = name == null ? null : name.trim();
        }
    }
    @Schema(requiredProperties = {"code", "name", "facultyId"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record DepartmentWrite(@NotBlank @Size(max = 64) @Schema(pattern = "\\S") String code,
            @NotBlank @Size(max = 255) @Schema(pattern = "\\S") String name, @NotNull @Min(1) Long facultyId) implements StrictWrite {
        public DepartmentWrite {
            code = code == null ? null : code.trim();
            name = name == null ? null : name.trim();
        }
    }
}

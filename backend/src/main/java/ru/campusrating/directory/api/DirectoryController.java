package ru.campusrating.directory.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.directory.DepartmentInfo;
import ru.campusrating.directory.FacultyInfo;
import ru.campusrating.directory.internal.DirectoryQueries;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

@RestController
@RequestMapping("/api/v1/directory/faculties")
public class DirectoryController {
    private final DirectoryQueries directory;

    public DirectoryController(DirectoryQueries directory) { this.directory = directory; }

    @GetMapping
    public Items<FacultyInfo> listFaculties() {
        return new Items<>(directory.faculties());
    }

    @GetMapping("/{facultyId}/groups")
    public Items<StudyGroupView> listFacultyGroups(@PathVariable long facultyId) {
        requireFaculty(facultyId);
        return new Items<>(directory.groupsOfFaculty(facultyId).stream()
                .map(group -> new StudyGroupView(group.id(), group.name(), group.faculty())).toList());
    }

    @GetMapping("/{facultyId}/departments")
    public Items<DepartmentInfo> listFacultyDepartments(@PathVariable long facultyId) {
        requireFaculty(facultyId);
        return new Items<>(directory.departmentsOfFaculty(facultyId));
    }

    private void requireFaculty(long id) {
        if (!directory.facultyExists(id)) {
            throw new ApiException(ProblemCode.NOT_FOUND, "Факультет не найден");
        }
    }

    @io.swagger.v3.oas.annotations.media.Schema(requiredProperties = "items")
    public record Items<T>(List<T> items) {}
    @io.swagger.v3.oas.annotations.media.Schema(requiredProperties = {"id", "name", "faculty"})
    public record StudyGroupView(@jakarta.validation.constraints.Min(1) long id, String name, FacultyInfo faculty) {}
}

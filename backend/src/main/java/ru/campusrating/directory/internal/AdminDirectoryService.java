package ru.campusrating.directory.internal;

import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.directory.DepartmentInfo;
import ru.campusrating.directory.FacultyInfo;
import ru.campusrating.directory.api.AdminDirectoryController.AdminStudyGroup;
import ru.campusrating.directory.api.AdminDirectoryController.DepartmentWrite;
import ru.campusrating.directory.api.AdminDirectoryController.FacultyWrite;
import ru.campusrating.directory.api.AdminDirectoryController.StudyGroupWrite;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.FieldError;
import ru.campusrating.shared.error.ProblemCode;
import ru.campusrating.shared.error.ValidationFailedException;

@Service
public class AdminDirectoryService {
    private final AdminDirectoryRepository repository;
    private final DirectoryQueries directory;
    AdminDirectoryService(AdminDirectoryRepository repository, DirectoryQueries directory) {
        this.repository = repository; this.directory = directory;
    }

    public List<FacultyInfo> faculties() { return directory.faculties(); }
    public List<AdminStudyGroup> groups() { return repository.groups(); }
    public List<DepartmentInfo> departments() { return repository.departments(); }

    @Transactional
    public FacultyInfo writeFaculty(Long id, FacultyWrite request) {
        long saved = save(Kind.FACULTY, id, request.code(), request.name(), null, null);
        return directory.faculty(saved).orElseThrow();
    }

    @Transactional
    public AdminStudyGroup writeStudyGroup(Long id, StudyGroupWrite request) {
        long saved = save(Kind.STUDY_GROUP, id, request.code(), request.name(), request.facultyId(), request.admissionYear());
        return repository.group(saved).orElseThrow();
    }

    @Transactional
    public DepartmentInfo writeDepartment(Long id, DepartmentWrite request) {
        long saved = save(Kind.DEPARTMENT, id, request.code(), request.name(), request.facultyId(), null);
        return directory.department(saved).orElseThrow();
    }

    private long save(Kind kind, Long id, String code, String name, Long faculty, Integer year) {
        if (id != null) requireExists(kind, id);
        if (faculty != null && directory.faculty(faculty).isEmpty()) throw invalidFaculty();
        if (repository.codeTaken(kind, id, code)) throw codeTaken();
        try {
            return repository.save(kind, id, code, name, faculty, year).orElseThrow(this::notFound);
        } catch (DuplicateKeyException exception) {
            throw codeTaken();
        } catch (DataIntegrityViolationException exception) {
            if (hasSqlState(exception, "23503")) throw invalidFaculty();
            throw exception;
        }
    }

    @Transactional
    public void delete(Kind kind, long id) {
        requireExists(kind, id);
        if (repository.inUse(kind, id)) throw inUse();
        try {
            if (!repository.delete(kind, id)) throw notFound();
        } catch (DataIntegrityViolationException exception) {
            if (hasSqlState(exception, "23503")) throw inUse();
            throw exception;
        }
    }

    private void requireExists(Kind kind, long id) { if (!repository.exists(kind, id)) throw notFound(); }
    private ApiException notFound() { return new ApiException(ProblemCode.NOT_FOUND, "Запись справочника не найдена"); }
    private ApiException codeTaken() { return new ApiException(ProblemCode.DIRECTORY_CODE_TAKEN, "Код уже используется"); }
    private ApiException inUse() { return new ApiException(ProblemCode.DIRECTORY_IN_USE, "Нельзя удалить используемую запись справочника"); }
    private ValidationFailedException invalidFaculty() {
        return new ValidationFailedException("Факультет не найден", List.of(new FieldError("facultyId", "INVALID", "Факультет не найден")));
    }
    private boolean hasSqlState(Throwable exception, String state) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && state.equals(sql.getSQLState())) return true;
        }
        return false;
    }

    public enum Kind {
        FACULTY("faculty"), STUDY_GROUP("study_group"), DEPARTMENT("department");
        final String table;
        Kind(String table) { this.table = table; }
    }
}

package ru.campusrating.directory;

import java.util.Optional;

public interface DirectoryImport {
    record ImportedGroup(long id,long facultyId,String code,String name,int admissionYear) {}
    Optional<FacultyInfo> facultyByCode(String code);
    Optional<DepartmentInfo> departmentByCode(String code);
    Optional<ImportedGroup> groupByCode(String code);
    void createGroup(long facultyId,String code,String name,int admissionYear);
}

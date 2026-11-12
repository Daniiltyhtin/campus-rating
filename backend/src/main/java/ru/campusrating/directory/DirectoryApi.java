package ru.campusrating.directory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface DirectoryApi {
    Optional<FacultyInfo> faculty(long facultyId);
    Optional<GroupInfo> group(long groupId);
    Map<Long, GroupInfo> groups(Collection<Long> groupIds);
    List<GroupInfo> groupsOfFaculty(long facultyId);
    List<GroupInfo> allGroups();
    Optional<DepartmentInfo> department(long departmentId);
    Map<Long, DepartmentInfo> departments(Collection<Long> departmentIds);
}

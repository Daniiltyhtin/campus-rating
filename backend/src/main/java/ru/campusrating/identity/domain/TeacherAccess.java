package ru.campusrating.identity.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import ru.campusrating.directory.GroupInfo;

public final class TeacherAccess {
    private TeacherAccess() {}

    public static Set<Long> groups(boolean approved, long facultyId, Collection<GroupInfo> groups,
                                    Collection<TeacherGrant> grants, LocalDate date) {
        if (!approved) return Set.of();
        var result = new HashSet<Long>();
        int academicYear = date.getMonthValue() >= 9 ? date.getYear() : date.getYear() - 1;
        for (var group : groups) {
            if (group.faculty().id() == facultyId || grants.stream().anyMatch(grant -> matches(grant, group, academicYear))) {
                result.add(group.id());
            }
        }
        return Set.copyOf(result);
    }

    private static boolean matches(TeacherGrant grant, GroupInfo group, int academicYear) {
        int course = academicYear - group.admissionYear() + 1;
        return switch (grant) {
            case TeacherGrant.Group target -> group.id() == target.groupId();
            case TeacherGrant.Faculty target -> group.faculty().id() == target.facultyId();
            case TeacherGrant.FacultyCourse target -> group.faculty().id() == target.facultyId() && course == target.course();
            case TeacherGrant.Course target -> course == target.course();
        };
    }
}

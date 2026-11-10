package ru.campusrating.identity;

import java.util.Collection;
import java.util.Set;

public interface TeacherScope {
    Set<Long> accessibleGroupIds(long teacherId);
    boolean isAccessible(long teacherId, long groupId);
    void requireAccessible(long teacherId, Collection<Long> groupIds);
}

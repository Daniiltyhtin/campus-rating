package ru.campusrating.identity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface GroupMembers {
    List<StudentMember> activeStudents(Collection<Long> groupIds);
    Optional<StudentMember> membership(long studentId);
    Map<Long, StudentMember> memberships(Collection<Long> studentIds);
    Map<Long, UserRef> users(Collection<Long> userIds);
    Map<Long, GroupRef> groups(Collection<Long> groupIds);
    Optional<UserCard> card(long userId);
}

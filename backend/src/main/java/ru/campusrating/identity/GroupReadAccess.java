package ru.campusrating.identity;

import java.util.List;
import org.springframework.stereotype.Service;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

@Service
public class GroupReadAccess {
    private final GroupMembers members;
    private final CurrentUser current;
    private final TeacherScope scope;

    public GroupReadAccess(GroupMembers members, CurrentUser current, TeacherScope scope) {
        this.members = members;
        this.current = current;
        this.scope = scope;
    }

    public GroupRef require(long groupId) {
        var ids = List.of(groupId);
        var group = members.groups(ids).get(groupId);
        if (group == null) throw new ApiException(ProblemCode.NOT_FOUND, "Группа не найдена");
        var viewer = current.require();
        switch (viewer.role()) {
            case ADMIN -> { }
            case TEACHER -> scope.requireAccessible(viewer.id(), ids);
            case STUDENT -> throw new ApiException(ProblemCode.FORBIDDEN, "Нет доступа");
        }
        return group;
    }
}

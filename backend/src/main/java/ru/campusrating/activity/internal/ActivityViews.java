package ru.campusrating.activity.internal;

import java.util.Comparator;
import ru.campusrating.activity.api.ActivityController.FacultyRef;
import ru.campusrating.activity.api.ActivityController.StudyGroupRef;
import ru.campusrating.identity.GroupRef;

final class ActivityViews {
    static final Comparator<GroupRef> BY_NAME = Comparator.comparing(GroupRef::name).thenComparingLong(GroupRef::id);

    private ActivityViews() {}

    static StudyGroupRef group(GroupRef group) {
        var faculty = new FacultyRef(group.facultyId(), group.facultyCode(), group.facultyName());
        return new StudyGroupRef(group.id(), group.name(), faculty);
    }
}

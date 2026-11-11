package ru.campusrating.checkpoint;

import java.util.List;

public interface StudentAssignments {
    List<StudentAssignmentView> active(long studentId);

    List<StudentAssignmentView> completed(long studentId);
}

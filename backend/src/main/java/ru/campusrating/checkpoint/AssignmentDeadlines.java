package ru.campusrating.checkpoint;

import java.time.Instant;
import java.util.List;

public interface AssignmentDeadlines {
    List<DueAssignment> inProgressDueBy(Instant now, Instant until);

    record DueAssignment(long assignmentId, long studentId, String checkpointTitle, Instant deadline) {}
}

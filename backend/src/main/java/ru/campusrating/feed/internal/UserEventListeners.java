package ru.campusrating.feed.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ru.campusrating.activity.ActivityCreated;
import ru.campusrating.activity.PointsAwarded;
import ru.campusrating.checkpoint.AssignmentAccepted;
import ru.campusrating.checkpoint.AssignmentCreated;
import ru.campusrating.checkpoint.AssignmentReturned;
import ru.campusrating.checkpoint.AssignmentSubmitted;
import ru.campusrating.feed.domain.UserEventKind;
import ru.campusrating.feed.internal.UserEventRepository.NewEvent;

@Component
public class UserEventListeners {
    private final UserEventRepository repository;

    UserEventListeners(UserEventRepository repository) {
        this.repository = repository;
    }

    @ApplicationModuleListener
    public void on(AssignmentCreated event) {
        repository.insert(new NewEvent(event.studentId(), UserEventKind.ASSIGNMENT_CREATED, "assignment:" + event.assignmentId(),
                event.occurredAt(), event.assignmentId(), event.checkpointTitle(), event.deadline(), null, null, null));
    }

    @ApplicationModuleListener
    public void on(AssignmentSubmitted event) {
        repository.insert(new NewEvent(event.authorId(), UserEventKind.WORK_SUBMITTED, "submitted:" + event.submissionId(),
                event.occurredAt(), event.checkpointId(), event.checkpointTitle(), null, null, null, event.studentFullName()));
    }

    @ApplicationModuleListener
    public void on(AssignmentAccepted event) {
        repository.insert(new NewEvent(event.studentId(), UserEventKind.WORK_ACCEPTED, "accepted:" + event.assignmentId(),
                event.occurredAt(), event.assignmentId(), event.checkpointTitle(), null, null, null, null));
    }

    @ApplicationModuleListener
    public void on(AssignmentReturned event) {
        repository.insert(new NewEvent(event.studentId(), UserEventKind.WORK_RETURNED, "returned:" + event.submissionId(),
                event.occurredAt(), event.assignmentId(), event.checkpointTitle(), null, null, null, null));
    }

    @ApplicationModuleListener
    public void on(ActivityCreated event) {
        for (long student : event.studentIds()) {
            repository.insert(new NewEvent(student, UserEventKind.ACTIVITY_CREATED, "activity:" + event.activityId(),
                    event.occurredAt(), event.activityId(), event.title(), null, null, null, null));
        }
    }

    @ApplicationModuleListener
    public void on(PointsAwarded event) {
        repository.insert(new NewEvent(event.studentId(), UserEventKind.POINTS_AWARDED, "award:" + event.entryId(),
                event.occurredAt(), event.activityId(), event.activityTitle(), null, event.amount(), event.currency(), null));
    }
}

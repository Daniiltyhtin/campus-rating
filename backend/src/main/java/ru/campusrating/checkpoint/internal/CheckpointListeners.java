package ru.campusrating.checkpoint.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ru.campusrating.checkpoint.CheckpointCreated;
import ru.campusrating.identity.MembershipActivated;

@Component
public class CheckpointListeners {
    private final AssignmentReconciliation assignments;
    CheckpointListeners(AssignmentReconciliation assignments) { this.assignments = assignments; }

    @ApplicationModuleListener public void on(CheckpointCreated event) { assignments.checkpoint(event.checkpointId()); }
    @ApplicationModuleListener public void on(MembershipActivated event) { assignments.activation(event); }
}

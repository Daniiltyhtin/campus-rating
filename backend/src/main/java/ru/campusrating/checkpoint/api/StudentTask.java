package ru.campusrating.checkpoint.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import ru.campusrating.checkpoint.AssignmentState;
import ru.campusrating.checkpoint.AssignmentStatus;
import ru.campusrating.checkpoint.api.AssignmentView.Penalty;
import ru.campusrating.checkpoint.api.CheckpointView.UserView;

@Schema(
        requiredProperties = {
            "assignmentId",
            "checkpoint",
            "teacher",
            "deadline",
            "cost",
            "state",
            "status",
            "penalty",
            "acceptedAt"
        })
public record StudentTask(
        @Min(1) long assignmentId,
        CheckpointTitle checkpoint,
        UserView teacher,
        Instant deadline,
        int cost,
        AssignmentState state,
        AssignmentStatus status,
        Penalty penalty,
        @Schema(
                        types = {"string", "null"},
                        format = "date-time")
                Instant acceptedAt) {
    @Schema(requiredProperties = {"id", "title"})
    public record CheckpointTitle(@Min(1) long id, String title) {}
}

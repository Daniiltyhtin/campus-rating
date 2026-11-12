package ru.campusrating.checkpoint.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import ru.campusrating.checkpoint.AssignmentState;
import ru.campusrating.checkpoint.AssignmentStatus;
import ru.campusrating.checkpoint.api.CheckpointView.StudyGroupView;
import ru.campusrating.checkpoint.api.CheckpointView.UserView;

@Schema(
        requiredProperties = {
            "assignmentId",
            "student",
            "group",
            "state",
            "status",
            "charged",
            "submittedAt",
            "submissionKind",
            "hasSubmissions"
        })
public record CheckpointAssignmentRow(
        @Min(1) long assignmentId,
        UserView student,
        StudyGroupView group,
        AssignmentState state,
        AssignmentStatus status,
        int charged,
        @Schema(
                        types = {"string", "null"},
                        format = "date-time")
                Instant submittedAt,
        @Schema(implementation = NullableSubmissionKind.class) SubmissionKind submissionKind,
        boolean hasSubmissions) {
    public enum SubmissionKind {
        LINK,
        FILE
    }

    @Schema(anyOf = {SubmissionKind.class, AssignmentView.NullValue.class})
    public interface NullableSubmissionKind {}
}

package ru.campusrating.checkpoint.api;

import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import ru.campusrating.checkpoint.AssignmentState;
import ru.campusrating.checkpoint.AssignmentStatus;
import ru.campusrating.checkpoint.api.CheckpointView.StudyGroupView;
import ru.campusrating.checkpoint.api.CheckpointView.UserView;
import ru.campusrating.checkpoint.domain.PenaltyPhase;

@Schema(
        requiredProperties = {
            "id",
            "checkpoint",
            "student",
            "group",
            "state",
            "status",
            "penalty",
            "returnComment",
            "submission",
            "submissions",
            "submittedAt",
            "acceptedAt"
        })
public record AssignmentView(
        @Min(1) long id,
        CheckpointSummary checkpoint,
        UserView student,
        StudyGroupView group,
        AssignmentState state,
        AssignmentStatus status,
        Penalty penalty,
        @Schema(types = {"string", "null"}) String returnComment,
        @Schema(implementation = NullableSubmission.class) Submission submission,
        List<Submission> submissions,
        @Schema(
                        types = {"string", "null"},
                        format = "date-time")
                Instant submittedAt,
        @Schema(
                        types = {"string", "null"},
                        format = "date-time")
                Instant acceptedAt) {
    @Schema(requiredProperties = {"id", "title", "description", "deadline", "cost", "author"})
    public record CheckpointSummary(
            @Min(1) long id,
            String title,
            String description,
            Instant deadline,
            int cost,
            UserView author) {}

    @Schema(
            requiredProperties = {
                "charged",
                "cap",
                "step",
                "intervalSeconds",
                "phase",
                "nextAt",
                "nextAmount"
            })
    public record Penalty(
            @Min(0) int charged,
            int cap,
            int step,
            int intervalSeconds,
            PenaltyPhase phase,
            @Schema(
                            types = {"string", "null"},
                            format = "date-time")
                    Instant nextAt,
            @Min(0)
                    @Schema(
                            types = {"integer", "null"},
                            format = "int32")
                    Integer nextAmount) {}

    @Schema(anyOf = {Submission.class, NullValue.class})
    public interface NullableSubmission {}

    @Schema(types = "null", schemaResolution = Schema.SchemaResolution.INLINE)
    public interface NullValue {}

    @Schema(
            oneOf = {LinkSubmission.class, FileSubmission.class},
            discriminatorProperty = "kind",
            discriminatorMapping = {
                @DiscriminatorMapping(value = "LINK", schema = LinkSubmission.class),
                @DiscriminatorMapping(value = "FILE", schema = FileSubmission.class)
            })
    public sealed interface Submission permits LinkSubmission, FileSubmission {}

    @Schema(
            requiredProperties = {
                "id",
                "kind",
                "url",
                "submittedAt",
                "returnComment",
                "returnedAt"
            })
    public record LinkSubmission(
            @Min(1) long id,
            @Schema(_const = "LINK") String kind,
            @Schema(format = "uri") String url,
            Instant submittedAt,
            @Schema(types = {"string", "null"}) String returnComment,
            @Schema(
                            types = {"string", "null"},
                            format = "date-time")
                    Instant returnedAt)
            implements Submission {}

    @Schema(
            requiredProperties = {
                "id",
                "kind",
                "fileName",
                "sizeBytes",
                "contentType",
                "submittedAt",
                "returnComment",
                "returnedAt"
            })
    public record FileSubmission(
            @Min(1) long id,
            @Schema(_const = "FILE") String kind,
            String fileName,
            long sizeBytes,
            String contentType,
            Instant submittedAt,
            @Schema(types = {"string", "null"}) String returnComment,
            @Schema(
                            types = {"string", "null"},
                            format = "date-time")
                    Instant returnedAt)
            implements Submission {}
}

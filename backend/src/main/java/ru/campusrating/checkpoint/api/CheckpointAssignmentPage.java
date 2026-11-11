package ru.campusrating.checkpoint.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = {"items", "nextCursor", "statusCounts"})
public record CheckpointAssignmentPage(
        List<CheckpointAssignmentRow> items,
        @Schema(types = {"string", "null"}) String nextCursor,
        StatusCounts statusCounts) {
    @Schema(requiredProperties = {"IN_PROGRESS", "SUBMITTED", "OVERDUE", "ACCEPTED"})
    public record StatusCounts(int IN_PROGRESS, int SUBMITTED, int OVERDUE, int ACCEPTED) {}
}

package ru.campusrating.checkpoint.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;

@Schema(requiredProperties = {"id", "title", "description", "deadline", "cost", "penaltyStep",
        "penaltyIntervalSeconds", "groups", "author", "createdAt", "submittedCount", "totalCount"})
public record CheckpointView(@Min(1) long id, String title, String description, Instant deadline, int cost,
                             int penaltyStep, int penaltyIntervalSeconds, List<StudyGroupView> groups,
                             UserView author, Instant createdAt, int submittedCount, int totalCount) {
    @Schema(requiredProperties = {"id", "name", "faculty"})
    public record StudyGroupView(@Min(1) long id, String name, FacultyView faculty) {}
    @Schema(requiredProperties = {"id", "code", "name"})
    public record FacultyView(@Min(1) long id, String code, String name) {}
    @Schema(requiredProperties = {"id", "fullName"})
    public record UserView(@Min(1) long id, String fullName) {}
}

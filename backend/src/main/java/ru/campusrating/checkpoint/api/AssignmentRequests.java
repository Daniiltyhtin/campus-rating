package ru.campusrating.checkpoint.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class AssignmentRequests {
    private AssignmentRequests() {}

    @Schema(requiredProperties = "comment")
    public record Return(
            @NotBlank(message = "Нужен комментарий") @Size(min = 1, max = 2000) String comment) {}

    @Schema(requiredProperties = "assignmentIds")
    public record Accept(
            @NotEmpty(message = "Выберите хотя бы одну работу") @Size(min = 1, max = 500)
                    List<@NotNull @Min(1) Long> assignmentIds) {}

    @Schema(requiredProperties = {"accepted", "skipped"})
    public record Accepted(List<@Min(1) Long> accepted, List<Skipped> skipped) {}

    @Schema(requiredProperties = {"assignmentId", "code"})
    public record Skipped(
            @Min(1) long assignmentId, @Schema(allowableValues = "INVALID_STATE") String code) {}
}

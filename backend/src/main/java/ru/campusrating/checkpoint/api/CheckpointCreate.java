package ru.campusrating.checkpoint.api;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.time.Instant;
import java.util.List;

@Schema(requiredProperties = {"title", "description", "groupIds", "deadline", "cost"})
public record CheckpointCreate(
        @NotBlank @Size(min = 1, max = 200) String title,
        @NotNull @Size(max = 10000) String description,
        @NotEmpty @ArraySchema(minItems = 1, uniqueItems = true, schema = @Schema(type = "integer", format = "int64", minimum = "1"))
        List<@NotNull @Min(1) Long> groupIds,
        @NotNull Instant deadline,
        @NotNull @Min(1) @Max(1000) @JsonDeserialize(using = CheckpointCostDeserializer.class) Integer cost) {}

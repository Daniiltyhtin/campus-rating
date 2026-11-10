package ru.campusrating.directory;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;

@Schema(requiredProperties = {"id", "code", "name"})
public record FacultyInfo(@Min(1) long id, String code, String name) {}

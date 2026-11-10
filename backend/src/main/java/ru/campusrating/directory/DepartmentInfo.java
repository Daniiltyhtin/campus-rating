package ru.campusrating.directory;

@io.swagger.v3.oas.annotations.media.Schema(requiredProperties = {"id", "code", "name", "faculty"})
public record DepartmentInfo(@jakarta.validation.constraints.Min(1) long id, String code, String name, FacultyInfo faculty) {}

package ru.campusrating.checkpoint.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import ru.campusrating.checkpoint.internal.CheckpointValidation;

@Schema(minProperties = 1, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class CheckpointUpdate {
    private String title;
    private String description;

    @Size(min = 1, max = 200)
    public String getTitle() { return title; }
    @Size(max = 10000)
    public String getDescription() { return description; }

    @JsonSetter public void setTitle(String title) {
        if (title == null) throw CheckpointValidation.invalid("title", "Обязательное значение");
        if (title.isBlank()) throw CheckpointValidation.invalid("title", "Название не должно быть пустым");
        this.title = title;
    }
    @JsonSetter public void setDescription(String description) {
        if (description == null) throw CheckpointValidation.invalid("description", "Обязательное значение");
        this.description = description;
    }
    @JsonAnySetter public void unknown(String field, Object value) {
        throw CheckpointValidation.invalid(field, "Неизвестное поле");
    }
}

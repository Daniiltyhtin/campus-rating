package ru.campusrating.checkpoint.internal;

import java.util.List;
import ru.campusrating.shared.error.FieldError;
import ru.campusrating.shared.error.ValidationFailedException;

public final class CheckpointValidation {
    private CheckpointValidation() {}
    public static ValidationFailedException invalid(String field, String message) {
        return new ValidationFailedException("Проверьте поля запроса", List.of(new FieldError(field, "INVALID", message)));
    }
}

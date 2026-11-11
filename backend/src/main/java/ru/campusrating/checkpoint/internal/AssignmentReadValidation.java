package ru.campusrating.checkpoint.internal;

import java.time.DateTimeException;
import ru.campusrating.checkpoint.domain.AssignmentCursor;

final class AssignmentReadValidation {
    private AssignmentReadValidation() {}

    static int limit(String value) {
        try {
            int limit = value == null ? 20 : Integer.parseInt(value);
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException();
            }
            return limit;
        } catch (IllegalArgumentException exception) {
            throw CheckpointValidation.invalid("limit", "Размер страницы от 1 до 100");
        }
    }

    static AssignmentCursor cursor(String value, String filter) {
        if (value == null) {
            return null;
        }
        try {
            var cursor = AssignmentCursor.decode(value, filter);
            if (filter.startsWith("completed/")) {
                cursor.acceptedAt();
            }
            return cursor;
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw CheckpointValidation.invalid("cursor", "Неверный курсор списка");
        }
    }
}

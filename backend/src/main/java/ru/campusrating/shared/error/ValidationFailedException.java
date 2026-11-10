package ru.campusrating.shared.error;

import java.util.List;

public final class ValidationFailedException extends ApiException {
    private final List<FieldError> fieldErrors;

    public ValidationFailedException(String detail, List<FieldError> fieldErrors) {
        super(ProblemCode.VALIDATION_FAILED, detail);
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public List<FieldError> fieldErrors() { return fieldErrors; }
}

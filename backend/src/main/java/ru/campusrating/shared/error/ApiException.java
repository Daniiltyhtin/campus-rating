package ru.campusrating.shared.error;

public class ApiException extends RuntimeException {
    private final ProblemCode code;

    public ApiException(ProblemCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ProblemCode code() { return code; }
}

package ru.campusrating.shared.error;

import java.util.List;

public final class StudentsRejectedException extends ApiException {
    private final List<Long> studentIds;

    public StudentsRejectedException(ProblemCode code, String detail, List<Long> studentIds) {
        super(code, detail);
        if (code != ProblemCode.NOT_ENROLLED && code != ProblemCode.STUDENT_NOT_ELIGIBLE
                && code != ProblemCode.ALREADY_CONFIRMED) {
            throw new IllegalArgumentException("Unsupported students problem code: " + code);
        }
        this.studentIds = List.copyOf(studentIds);
    }

    public List<Long> studentIds() { return studentIds; }
}

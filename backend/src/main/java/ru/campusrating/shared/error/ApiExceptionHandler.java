package ru.campusrating.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;
import java.net.URI;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ProblemDetail api(ApiException exception, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(exception.code().status()), exception.getMessage());
        problem.setType(URI.create("about:blank"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", exception.code().name());
        if (exception instanceof ValidationFailedException validation) {
            problem.setProperty("fieldErrors", validation.fieldErrors());
        }
        if (exception instanceof BudgetExceededException budget) {
            problem.setProperty("budgets", budget.budgets());
        }
        if (exception instanceof StudentsRejectedException students) {
            problem.setProperty("studentIds", students.studentIds());
        }
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail bodyValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        var errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), validationCode(error.getCode(), error.contains(ConstraintViolation.class)
                        ? error.unwrap(ConstraintViolation.class) : null),
                        error.getDefaultMessage())).toList();
        return validation(errors, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail parameterValidation(HandlerMethodValidationException exception, HttpServletRequest request) {
        var errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream().map(error -> new FieldError(
                        result.getMethodParameter().getParameterName(),
                        validationCode(error.getCodes()[0], result.unwrap(error, ConstraintViolation.class)),
                        error.getDefaultMessage())))
                .toList();
        return validation(errors, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail parameterType(MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return validation(List.of(new FieldError(exception.getName(), "INVALID", "Неверный формат параметра")), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadableBody(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return validation(List.of(new FieldError("body", "INVALID", "Неверный формат запроса")), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail missingResource(NoResourceFoundException exception, HttpServletRequest request) {
        return api(new ApiException(ProblemCode.NOT_FOUND, "Ресурс не найден"), request);
    }

    private ProblemDetail validation(List<FieldError> errors, HttpServletRequest request) {
        return api(new ValidationFailedException("Проверьте поля запроса", errors), request);
    }

    private String validationCode(String constraint, ConstraintViolation<?> violation) {
        if (constraint != null && (constraint.startsWith("NotNull") || constraint.startsWith("NotBlank")
                || constraint.startsWith("NotEmpty"))) {
            return "REQUIRED";
        }
        if (violation != null) {
            var descriptor = violation.getConstraintDescriptor();
            var name = descriptor.getAnnotation().annotationType().getSimpleName();
            if (name.equals("Size") || name.equals("Length")) {
                int size = sizeOf(violation.getInvalidValue());
                var attributes = descriptor.getAttributes();
                if (size >= 0 && size < (Integer) attributes.get("min")) {
                    return "TOO_SHORT";
                }
                if (size > (Integer) attributes.get("max")) {
                    return "TOO_LONG";
                }
            }
        }
        return "INVALID";
    }

    private int sizeOf(Object value) {
        return switch (value) {
            case CharSequence text -> text.length();
            case Collection<?> collection -> collection.size();
            case Map<?, ?> map -> map.size();
            case null -> -1;
            default -> value.getClass().isArray() ? Array.getLength(value) : -1;
        };
    }
}

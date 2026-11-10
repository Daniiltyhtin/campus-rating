package ru.campusrating.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;
import java.net.URI;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.ErrorResponse;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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
        var result = exception.getBindingResult();
        var fields = result.getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), validationCode(error.getCode(), error.contains(ConstraintViolation.class)
                        ? error.unwrap(ConstraintViolation.class) : null),
                        error.getDefaultMessage()));
        // Ограничение уровня класса относится ко всему объекту: поле — имя объекта, чтобы fieldErrors не был пуст.
        var global = result.getGlobalErrors().stream()
                .map(error -> new FieldError(error.getObjectName(), validationCode(error.getCode(), error.contains(ConstraintViolation.class)
                        ? error.unwrap(ConstraintViolation.class) : null),
                        error.getDefaultMessage()));
        return validation(Stream.concat(fields, global).toList(), request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail parameterValidation(HandlerMethodValidationException exception, HttpServletRequest request) {
        var errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream().map(error -> new FieldError(
                        // У @Valid @RequestBody рядом с ограничением параметра ошибки — поля тела, а не имя параметра.
                        error instanceof org.springframework.validation.FieldError field ? field.getField()
                                : result.getMethodParameter().getParameterName(),
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

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail missingParameter(MissingServletRequestParameterException exception, HttpServletRequest request) {
        return validation(List.of(new FieldError(exception.getParameterName(), "REQUIRED", "Обязательный параметр")), request);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ProblemDetail missingPart(MissingServletRequestPartException exception, HttpServletRequest request) {
        return validation(List.of(new FieldError(exception.getRequestPartName(), "REQUIRED", "Обязательная часть запроса")), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail uploadTooLarge(MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return api(new ApiException(ProblemCode.FILE_TOO_LARGE, "Файл больше 10 МБ"), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail missingResource(NoResourceFoundException exception, HttpServletRequest request) {
        return api(new ApiException(ProblemCode.NOT_FOUND, "Ресурс не найден"), request);
    }

    // Исключения Spring MVC и Spring Security идут дальше: их статусы отдают Spring и SecurityConfig (мастер-U5).
    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception exception, HttpServletRequest request) throws Exception {
        if (exception instanceof ErrorResponse || exception.getClass().getName().startsWith("org.springframework.security.")) {
            throw exception;
        }
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), exception);
        return api(new ApiException(ProblemCode.INTERNAL_ERROR, "Внутренняя ошибка сервера"), request);
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

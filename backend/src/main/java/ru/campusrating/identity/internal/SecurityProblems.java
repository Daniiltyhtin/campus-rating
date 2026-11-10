package ru.campusrating.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ru.campusrating.shared.error.ProblemCode;
import tools.jackson.databind.ObjectMapper;

@Component
class SecurityProblems {
    private final ObjectMapper mapper;
    SecurityProblems(ObjectMapper mapper) { this.mapper = mapper; }
    void write(HttpServletRequest request, HttpServletResponse response, ProblemCode code) throws IOException {
        response.setStatus(code.status());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), Map.of("type", "about:blank", "title", HttpStatus.valueOf(code.status()).getReasonPhrase(),
                "status", code.status(), "code", code.name(), "detail", detail(code), "instance", request.getRequestURI()));
    }
    private String detail(ProblemCode code) {
        return switch (code) {
            case UNAUTHENTICATED -> "Войдите в систему";
            case PASSWORD_CHANGE_REQUIRED -> "Сначала смените временный пароль";
            case CSRF_INVALID -> "Обновите страницу и повторите действие";
            default -> "Недостаточно прав";
        };
    }
}

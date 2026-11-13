package ru.campusrating.identity.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import ru.campusrating.shared.error.FieldError;
import ru.campusrating.shared.error.ValidationFailedException;

@Component
class Passwords {
    private final PasswordEncoder encoder;
    Passwords(PasswordEncoder encoder) { this.encoder = encoder; }

    void requireByteLimit(String password, String field) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalid(field, "TOO_LONG", "Пароль должен занимать не больше 72 байт в UTF-8");
        }
    }
    String hash(String password, String field) {
        requireValid(password, field);
        return encoder.encode(password);
    }
    void requireValid(String password, String field) {
        requireByteLimit(password, field);
        if (password.codePointCount(0, password.length()) < 8) throw invalid(field, "TOO_SHORT", "Не меньше 8 символов");
    }
    String hashIfValid(String password) {
        try {
            requireValid(password, "password");
        } catch (ValidationFailedException invalid) {
            return null;
        }
        return encoder.encode(password);
    }
    boolean matches(String password, String hash) { return hash != null && encoder.matches(password, hash); }
    static ValidationFailedException invalid(String field, String code, String message) {
        return new ValidationFailedException("Проверьте поля запроса", List.of(new FieldError(field, code, message)));
    }
}

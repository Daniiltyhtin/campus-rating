package ru.campusrating.checkpoint.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ProblemDetail;
import ru.campusrating.shared.error.ApiExceptionHandler;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import ru.campusrating.checkpoint.internal.CheckpointService;
import ru.campusrating.settings.SettingsApi;
import ru.campusrating.shared.error.ValidationFailedException;

@RestController
@RequestMapping("/api/v1/checkpoints")
public class CheckpointController {
    private final CheckpointService checkpoints;
    private final SettingsApi settings;
    private final ApiExceptionHandler errors;

    public CheckpointController(CheckpointService checkpoints, SettingsApi settings, ApiExceptionHandler errors) {
        this.checkpoints = checkpoints;
        this.settings = settings;
        this.errors = errors;
    }

    @GetMapping public CheckpointList listMyCheckpoints() { return new CheckpointList(checkpoints.list()); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public CheckpointView createCheckpoint(@Valid @RequestBody CheckpointCreate request) { return checkpoints.create(request); }
    @GetMapping("/defaults") public CheckpointDefaults getCheckpointDefaults() {
        return new CheckpointDefaults(settings.penaltyStep(), Math.toIntExact(settings.penaltyInterval().toSeconds()),
                Math.toIntExact(settings.minPenaltyInterval().toSeconds()));
    }
    @GetMapping("/{checkpointId}") public CheckpointView getCheckpoint(@PathVariable @Min(1) long checkpointId) {
        return checkpoints.get(checkpointId);
    }
    @PatchMapping("/{checkpointId}")
    public CheckpointView updateCheckpoint(@PathVariable @Min(1) long checkpointId, @Valid @RequestBody CheckpointUpdate request) {
        return checkpoints.update(checkpointId, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail invalidBody(HttpMessageNotReadableException exception, HttpServletRequest request) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ValidationFailedException validation) return errors.api(validation, request);
        }
        return errors.unreadableBody(exception, request);
    }

    @Schema(requiredProperties = "items")
    public record CheckpointList(List<CheckpointView> items) {}
    @Schema(requiredProperties = {"penaltyStep", "penaltyIntervalSeconds", "minPenaltyIntervalSeconds"})
    public record CheckpointDefaults(int penaltyStep, int penaltyIntervalSeconds, int minPenaltyIntervalSeconds) {}
}

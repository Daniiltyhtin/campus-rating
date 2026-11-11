package ru.campusrating.checkpoint.api;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.checkpoint.AssignmentStatus;
import ru.campusrating.checkpoint.internal.AssignmentReadService;
import ru.campusrating.checkpoint.internal.CheckpointValidation;
import ru.campusrating.checkpoint.internal.StudentAssignmentsService;

@RestController
public class AssignmentReadController {
    private final StudentAssignmentsService students;
    private final AssignmentReadService assignments;

    public AssignmentReadController(
            StudentAssignmentsService students, AssignmentReadService assignments) {
        this.students = students;
        this.assignments = assignments;
    }

    @GetMapping("/api/v1/me/assignments")
    public StudentTaskPage listMyAssignments(
            @Parameter(schema = @Schema(allowableValues = {"active", "completed"})) @RequestParam
                    String view,
            @Parameter(schema = @Schema(minLength = 1)) @RequestParam(required = false)
                    String cursor,
            @Parameter(
                            schema =
                                    @Schema(
                                            type = "integer",
                                            format = "int32",
                                            minimum = "1",
                                            maximum = "100",
                                            defaultValue = "20"))
                    @RequestParam(required = false)
                    String limit) {
        return students.mine(view, cursor, limit);
    }

    @GetMapping("/api/v1/checkpoints/{checkpointId}/assignments")
    public CheckpointAssignmentPage listCheckpointAssignments(
            @PathVariable @Min(1) long checkpointId,
            @Parameter(schema = @Schema(implementation = AssignmentStatus.class))
                    @RequestParam(required = false)
                    String status,
            @RequestParam(required = false) @Min(1) Long groupId,
            @Parameter(schema = @Schema(minLength = 1)) @RequestParam(required = false)
                    String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return assignments.table(checkpointId, status(status), groupId, cursor, limit);
    }

    private AssignmentStatus status(String value) {
        if (value == null) {
            return null;
        }
        try {
            return AssignmentStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw CheckpointValidation.invalid("status", "Укажите статус назначения");
        }
    }
}

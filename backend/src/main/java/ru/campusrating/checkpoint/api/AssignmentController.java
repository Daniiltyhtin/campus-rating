package ru.campusrating.checkpoint.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.campusrating.checkpoint.AssignmentState;
import ru.campusrating.checkpoint.domain.InvalidAssignmentStateException;
import ru.campusrating.checkpoint.domain.SubmissionRules;
import ru.campusrating.checkpoint.internal.CheckpointValidation;
import ru.campusrating.checkpoint.internal.SubmissionService;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ApiExceptionHandler;
import ru.campusrating.shared.error.ProblemCode;

@RestController
@RequestMapping("/api/v1")
public class AssignmentController {
    private final SubmissionService submissions;
    private final ApiExceptionHandler errors;

    public AssignmentController(SubmissionService submissions, ApiExceptionHandler errors) {
        this.submissions = submissions;
        this.errors = errors;
    }

    @GetMapping("/assignments/{assignmentId}")
    public AssignmentView getAssignment(@PathVariable @Min(1) long assignmentId) {
        return submissions.get(assignmentId);
    }

    @Operation(
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    SubmissionUpload.class),
                                            encoding =
                                                    @Encoding(
                                                            name = "file",
                                                            contentType =
                                                                    "application/pdf,"
                                                                        + " application/vnd.openxmlformats-officedocument.wordprocessingml.document,"
                                                                        + " application/zip,"
                                                                        + " image/png,"
                                                                        + " image/jpeg"))))
    @PostMapping(
            value = "/assignments/{assignmentId}/submission",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AssignmentView submitAssignment(
            @PathVariable @Min(1) long assignmentId,
            @Parameter(hidden = true) @RequestParam(required = false) String link,
            @RequestPart(required = false) MultipartFile file)
            throws IOException {
        if ((link == null) == (file == null)) {
            throw CheckpointValidation.invalid("link", "Укажите ссылку или файл");
        }
        return submissions.submit(assignmentId, link, file);
    }

    @PostMapping("/assignments/{assignmentId}/return")
    public AssignmentView returnAssignment(
            @PathVariable @Min(1) long assignmentId,
            @Valid @RequestBody AssignmentRequests.Return request) {
        return submissions.returnBack(assignmentId, request.comment());
    }

    @PostMapping("/checkpoints/{checkpointId}/assignments/accept")
    public AssignmentRequests.Accepted acceptAssignments(
            @PathVariable @Min(1) long checkpointId,
            @Valid @RequestBody AssignmentRequests.Accept request) {
        return submissions.accept(checkpointId, request.assignmentIds());
    }

    @Operation(
            responses =
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                            schema = @Schema(type = "string", format = "binary"))))
    @GetMapping("/assignments/{assignmentId}/submission/file")
    public ResponseEntity<Resource> downloadSubmissionFile(@PathVariable @Min(1) long assignmentId)
            throws IOException {
        return download(submissions.download(assignmentId, null));
    }

    @Operation(
            responses =
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                            schema = @Schema(type = "string", format = "binary"))))
    @GetMapping("/assignments/{assignmentId}/submissions/{submissionId}/file")
    public ResponseEntity<Resource> downloadSubmissionVersionFile(
            @PathVariable @Min(1) long assignmentId, @PathVariable @Min(1) long submissionId)
            throws IOException {
        return download(submissions.download(assignmentId, submissionId));
    }

    private ResponseEntity<Resource> download(SubmissionService.Download file) {
        String ascii = file.fileName().replaceAll("[^A-Za-z0-9._-]", "_");
        String encoded =
                URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8)
                        .replace("+", "%20")
                        .replace("*", "%2A");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.sizeBytes())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded)
                .body(file.resource());
    }

    @ExceptionHandler(InvalidAssignmentStateException.class)
    public ProblemDetail invalidState(
            InvalidAssignmentStateException exception, HttpServletRequest request) {
        String detail = invalidStateDetail(exception);
        return errors.api(new ApiException(ProblemCode.INVALID_STATE, detail), request);
    }

    private String invalidStateDetail(InvalidAssignmentStateException exception) {
        if (exception.action() == InvalidAssignmentStateException.Action.RETURN_BACK) {
            return "Вернуть можно только сданную работу";
        }
        return exception.state() == AssignmentState.ACCEPTED
                ? "Работа уже принята"
                : "Работа уже сдана";
    }

    @ExceptionHandler(SubmissionRules.Rejected.class)
    public ProblemDetail rejected(SubmissionRules.Rejected exception, HttpServletRequest request) {
        ApiException failure =
                switch (exception.reason()) {
                    case LINK_INVALID -> CheckpointValidation.invalid(
                            "link", "Укажите ссылку http или https длиной до 2000 символов");
                    case FILE_TOO_LARGE -> new ApiException(
                            ProblemCode.FILE_TOO_LARGE, "Файл больше 10 МБ");
                    case FILE_TYPE_NOT_ALLOWED -> new ApiException(
                            ProblemCode.FILE_TYPE_NOT_ALLOWED, "Можно pdf, docx, zip, png или jpg");
                };
        return errors.api(failure, request);
    }
}

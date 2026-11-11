package ru.campusrating.checkpoint.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(oneOf = {SubmissionUpload.Link.class, SubmissionUpload.File.class})
public interface SubmissionUpload {
    @Schema(requiredProperties = "link")
    record Link(
            @Schema(format = "uri", maxLength = 2000) String link,
            @Schema(implementation = ForbiddenPart.class) Object file) {}

    @Schema(requiredProperties = "file")
    record File(
            @Schema(type = "string", format = "binary") String file,
            @Schema(implementation = ForbiddenPart.class) Object link) {}

    interface ForbiddenPart {}
}

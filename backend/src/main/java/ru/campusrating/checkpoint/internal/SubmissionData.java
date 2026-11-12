package ru.campusrating.checkpoint.internal;

import java.time.Instant;
import ru.campusrating.checkpoint.api.AssignmentView;

record SubmissionData(
        long id,
        String kind,
        String url,
        String fileKey,
        String fileName,
        Long sizeBytes,
        String contentType,
        Instant submittedAt,
        String returnComment,
        Instant returnedAt,
        Instant withdrawnAt) {
    AssignmentView.Submission view() {
        if (kind.equals("LINK")) {
            return new AssignmentView.LinkSubmission(
                    id, kind, url, submittedAt, returnComment, returnedAt, withdrawnAt);
        }
        return new AssignmentView.FileSubmission(
                id, kind, fileName, sizeBytes, contentType, submittedAt,
                returnComment, returnedAt, withdrawnAt);
    }
}

package ru.campusrating.checkpoint.internal;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.checkpoint.AssignmentStatus;
import ru.campusrating.checkpoint.api.CheckpointAssignmentPage;
import ru.campusrating.checkpoint.domain.AssignmentCursor;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.Role;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

@Service
public class AssignmentReadService {
    private final AssignmentReadRepository repository;
    private final CheckpointRepository checkpoints;
    private final AssignmentReader reader;
    private final CurrentUser current;
    private final Clock clock;

    AssignmentReadService(
            AssignmentReadRepository repository,
            CheckpointRepository checkpoints,
            AssignmentReader reader,
            CurrentUser current,
            Clock clock) {
        this.repository = repository;
        this.checkpoints = checkpoints;
        this.reader = reader;
        this.current = current;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CheckpointAssignmentPage table(
            long checkpointId,
            AssignmentStatus status,
            Long groupId,
            String cursorValue,
            int limit) {
        var checkpoint =
                checkpoints
                        .find(checkpointId)
                        .orElseThrow(
                                () -> new ApiException(ProblemCode.NOT_FOUND, "Точка не найдена"));
        var user = current.require();
        if (user.role() != Role.ADMIN
                && !(user.role() == Role.TEACHER && user.id() == checkpoint.authorId())) {
            throw new ApiException(ProblemCode.FORBIDDEN, "Нет доступа к назначениям точки");
        }
        String filter =
                "checkpoint/"
                        + checkpointId
                        + "/"
                        + (status == null ? "ALL" : status.name())
                        + "/"
                        + (groupId == null ? "ALL" : groupId);
        var cursor = AssignmentReadValidation.cursor(cursorValue, filter);
        var now = clock.instant();
        var rows = repository.table(checkpointId, status, groupId, cursor, limit + 1, now);
        var page = rows.subList(0, Math.min(rows.size(), limit));
        String next = null;
        if (rows.size() > limit) {
            var last = page.getLast().assignment();
            next = new AssignmentCursor(last.studentFullName(), last.id(), filter).encode();
        }
        return new CheckpointAssignmentPage(
                reader.table(page, now), next, repository.counts(checkpointId, groupId, now));
    }
}

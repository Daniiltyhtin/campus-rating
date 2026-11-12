package ru.campusrating.checkpoint.internal;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.campusrating.checkpoint.AssignmentAccepted;
import ru.campusrating.checkpoint.AssignmentReturned;
import ru.campusrating.checkpoint.AssignmentState;
import ru.campusrating.checkpoint.AssignmentSubmitted;
import ru.campusrating.checkpoint.api.AssignmentRequests.Accepted;
import ru.campusrating.checkpoint.api.AssignmentRequests.Skipped;
import ru.campusrating.checkpoint.api.AssignmentView;
import ru.campusrating.checkpoint.domain.AssignmentTransitions;
import ru.campusrating.checkpoint.domain.InvalidAssignmentStateException;
import ru.campusrating.checkpoint.domain.SubmissionRules;
import ru.campusrating.identity.AuthenticatedUser;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.Role;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

@Service
public class SubmissionService {
    private final SubmissionRepository repository;
    private final CheckpointRepository checkpoints;
    private final AssignmentReader reader;
    private final SubmissionFiles files;
    private final CurrentUser current;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;

    SubmissionService(
            SubmissionRepository repository,
            CheckpointRepository checkpoints,
            AssignmentReader reader,
            SubmissionFiles files,
            CurrentUser current,
            Clock clock,
            ApplicationEventPublisher publisher) {
        this.repository = repository;
        this.checkpoints = checkpoints;
        this.reader = reader;
        this.files = files;
        this.current = current;
        this.clock = clock;
        this.publisher = publisher;
    }

    @Transactional(readOnly = true)
    public AssignmentView get(long id) {
        var assignment = require(id, false);
        var checkpoint = checkpoint(assignment.checkpointId());
        requireRead(assignment, checkpoint);
        return reader.view(assignment, checkpoint);
    }

    @Transactional(rollbackFor = IOException.class)
    public AssignmentView submit(long id, String link, MultipartFile file) throws IOException {
        var assignment = require(id, true);
        requireStudent(assignment);
        var target = AssignmentTransitions.submit(assignment.state());
        var now = clock.instant();
        long submission;
        if (file == null) {
            SubmissionRules.validateLink(link);
            submission = repository.link(id, link, now);
        } else {
            submission = files.save(id, file, now);
        }
        repository.state(id, target);
        var checkpoint = checkpoint(assignment.checkpointId());
        publisher.publishEvent(new AssignmentSubmitted(submission, checkpoint.id(), checkpoint.authorId(), checkpoint.title(),
                assignment.studentFullName(), now));
        return reader.view(require(id, false), checkpoint);
    }

    @Transactional
    public AssignmentView withdraw(long id) {
        var assignment = require(id, true);
        requireStudent(assignment);
        var checkpoint = checkpoint(assignment.checkpointId());
        var now = clock.instant();
        var target = AssignmentTransitions.withdraw(assignment.state(), checkpoint.deadline(), now);
        var latest = repository.versions(id).getFirst();
        repository.withdrawVersion(latest.id(), now);
        repository.state(id, target);
        return reader.view(require(id, false), checkpoint);
    }

    @Transactional
    public AssignmentView returnBack(long id, String comment) {
        var assignment = require(id, true);
        var checkpoint = checkpoint(assignment.checkpointId());
        requireAuthor(checkpoint);
        var target = AssignmentTransitions.returnBack(assignment.state());
        var latest = repository.versions(id).getFirst();
        var now = clock.instant();
        repository.returnVersion(latest.id(), comment, now);
        repository.state(id, target);
        publisher.publishEvent(new AssignmentReturned(latest.id(), id, assignment.studentId(), checkpoint.title(), now));
        return reader.view(require(id, false), checkpoint);
    }

    @Transactional
    public Accepted accept(long checkpointId, List<Long> ids) {
        var checkpoint = checkpoint(checkpointId);
        requireAuthor(checkpoint);
        var unique = ids.stream().distinct().sorted().toList();
        if (unique.isEmpty()) {
            throw CheckpointValidation.invalid("assignmentIds", "Выберите хотя бы одну работу");
        }
        var assignments = repository.lock(unique);
        validateBatch(checkpointId, unique, assignments);
        var accepted = new ArrayList<Long>();
        var skipped = new ArrayList<Skipped>();
        for (var assignment : assignments) {
            acceptOne(assignment, checkpoint, accepted, skipped);
        }
        return new Accepted(accepted, skipped);
    }

    private void validateBatch(long checkpoint, List<Long> ids, List<AssignmentData> assignments) {
        if (ids.size() != assignments.size()
                || assignments.stream().anyMatch(a -> a.checkpointId() != checkpoint)) {
            throw CheckpointValidation.invalid("assignmentIds", "Есть работы не из этой точки");
        }
    }

    private void acceptOne(AssignmentData assignment, CheckpointData checkpoint, List<Long> accepted, List<Skipped> skipped) {
        try {
            AssignmentTransitions.accept(assignment.state());
        } catch (InvalidAssignmentStateException exception) {
            skipped.add(new Skipped(assignment.id(), "INVALID_STATE"));
            return;
        }
        var now = clock.instant();
        repository.accept(assignment.id(), now);
        accepted.add(assignment.id());
        publisher.publishEvent(new AssignmentAccepted(assignment.id(), assignment.studentId(), checkpoint.title(), now));
    }

    @Transactional(readOnly = true)
    public Download download(long id, Long versionId) throws IOException {
        var assignment = require(id, false);
        requireRead(assignment, checkpoint(assignment.checkpointId()));
        var version =
                versionId == null
                        ? latest(id)
                        : repository.version(id, versionId).orElseThrow(this::notFound);
        if (!version.kind().equals("FILE")) {
            throw notFound();
        }
        return new Download(
                files.open(version),
                version.fileName(),
                version.contentType(),
                version.sizeBytes());
    }

    private SubmissionData latest(long id) {
        return repository.versions(id).stream().findFirst().orElseThrow(this::notFound);
    }

    private AssignmentData require(long id, boolean lock) {
        return repository.find(id, lock).orElseThrow(this::notFound);
    }

    private CheckpointData checkpoint(long id) {
        return checkpoints.find(id).orElseThrow(this::notFound);
    }

    private void requireStudent(AssignmentData assignment) {
        var user = current.require();
        if (user.role() != Role.STUDENT || assignment.studentId() != user.id()) {
            throw forbidden();
        }
    }

    private void requireAuthor(CheckpointData checkpoint) {
        var user = current.require();
        if (user.role() != Role.TEACHER || checkpoint.authorId() != user.id()) {
            throw forbidden();
        }
    }

    private void requireRead(AssignmentData assignment, CheckpointData checkpoint) {
        if (!canRead(current.require(), assignment, checkpoint)) {
            throw forbidden();
        }
    }

    private boolean canRead(
            AuthenticatedUser user, AssignmentData assignment, CheckpointData checkpoint) {
        return switch (user.role()) {
            case ADMIN -> true;
            case STUDENT -> assignment.studentId() == user.id();
            case TEACHER -> checkpoint.authorId() == user.id();
        };
    }

    private ApiException notFound() {
        return new ApiException(ProblemCode.NOT_FOUND, "Материалы сдачи не найдены");
    }

    private ApiException forbidden() {
        return new ApiException(ProblemCode.FORBIDDEN, "Нет доступа к назначению");
    }

    public record Download(
            Resource resource, String fileName, String contentType, long sizeBytes) {}
}

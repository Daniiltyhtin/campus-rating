package ru.campusrating.feed.internal;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.feed.api.PersonalFeedController.ActivityCreatedEvent;
import ru.campusrating.feed.api.PersonalFeedController.AssignmentCreatedEvent;
import ru.campusrating.feed.api.PersonalFeedController.DeadlineReminderEvent;
import ru.campusrating.feed.api.PersonalFeedController.PersonalFeedItem;
import ru.campusrating.feed.api.PersonalFeedController.PersonalFeedPage;
import ru.campusrating.feed.api.PersonalFeedController.PointsAwardedEvent;
import ru.campusrating.feed.api.PersonalFeedController.PollClosedEvent;
import ru.campusrating.feed.api.PersonalFeedController.PollCreatedEvent;
import ru.campusrating.feed.api.PersonalFeedController.WorkAcceptedEvent;
import ru.campusrating.feed.api.PersonalFeedController.WorkReturnedEvent;
import ru.campusrating.feed.api.PersonalFeedController.WorkSubmittedEvent;
import ru.campusrating.shared.domain.TimeIdCursor;
import ru.campusrating.feed.internal.UserEventRepository.EventRow;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.Role;
import ru.campusrating.shared.error.FieldError;
import ru.campusrating.shared.error.ValidationFailedException;

@Service
public class PersonalFeedService {
    private final UserEventRepository repository;
    private final CurrentUser current;

    PersonalFeedService(UserEventRepository repository, CurrentUser current) {
        this.repository = repository;
        this.current = current;
    }

    @Transactional(readOnly = true)
    public PersonalFeedPage list(@Nullable String cursor, int limit) {
        var user = current.require();
        var filter = "personal/" + user.id();
        var position = cursor == null ? null : decode(cursor, filter);
        if (user.role() == Role.ADMIN) {
            return new PersonalFeedPage(List.of(), null);
        }
        var rows = repository.page(user.id(), position, limit + 1);
        var page = rows.subList(0, Math.min(rows.size(), limit));
        String next = null;
        if (rows.size() > limit) {
            var last = page.getLast();
            next = new TimeIdCursor(last.createdAt(), last.id(), filter).encode();
        }
        return new PersonalFeedPage(page.stream().map(PersonalFeedService::item).toList(), next);
    }

    private static TimeIdCursor decode(String cursor, String filter) {
        try {
            return TimeIdCursor.decode(cursor, filter);
        } catch (IllegalArgumentException exception) {
            throw new ValidationFailedException("Неверный курсор",
                    List.of(new FieldError("cursor", "INVALID", "Неверный формат курсора")));
        }
    }

    private static PersonalFeedItem item(EventRow row) {
        return switch (row.kind()) {
            case ASSIGNMENT_CREATED -> new AssignmentCreatedEvent(row.id(), row.kind().name(), row.createdAt(), row.targetId(),
                    row.title(), Objects.requireNonNull(row.deadline()));
            case DEADLINE_REMINDER -> new DeadlineReminderEvent(row.id(), row.kind().name(), row.createdAt(), row.targetId(),
                    row.title(), Objects.requireNonNull(row.deadline()));
            case WORK_ACCEPTED -> new WorkAcceptedEvent(row.id(), row.kind().name(), row.createdAt(), row.targetId(), row.title());
            case WORK_RETURNED -> new WorkReturnedEvent(row.id(), row.kind().name(), row.createdAt(), row.targetId(), row.title());
            case POINTS_AWARDED -> new PointsAwardedEvent(row.id(), row.kind().name(), row.createdAt(), row.targetId(), row.title(),
                    Objects.requireNonNull(row.amount()), Objects.requireNonNull(row.currency()));
            case ACTIVITY_CREATED -> new ActivityCreatedEvent(row.id(), row.kind().name(), row.createdAt(), row.targetId(), row.title());
            case WORK_SUBMITTED -> new WorkSubmittedEvent(row.id(), row.kind().name(), row.createdAt(), row.targetId(), row.title(),
                    Objects.requireNonNull(row.studentFullName()));
            case POLL_CREATED -> new PollCreatedEvent(row.id(), row.kind().name(), row.createdAt(), row.targetId(), row.title(),
                    Objects.requireNonNull(row.deadline()));
            case POLL_CLOSED -> new PollClosedEvent(row.id(), row.kind().name(), row.createdAt(), row.targetId(), row.title(),
                    Objects.requireNonNull(row.voted()), Objects.requireNonNull(row.eligible()));
        };
    }
}

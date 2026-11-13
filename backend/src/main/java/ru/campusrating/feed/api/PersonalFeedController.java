package ru.campusrating.feed.api;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.feed.internal.PersonalFeedService;
import ru.campusrating.ledger.Currency;

@RestController
public class PersonalFeedController {
    private final PersonalFeedService service;

    public PersonalFeedController(PersonalFeedService service) {
        this.service = service;
    }

    @Schema(
            oneOf = {AssignmentCreatedEvent.class, DeadlineReminderEvent.class, WorkAcceptedEvent.class, WorkReturnedEvent.class,
                    PointsAwardedEvent.class, ActivityCreatedEvent.class, WorkSubmittedEvent.class},
            discriminatorProperty = "kind",
            discriminatorMapping = {
                @DiscriminatorMapping(value = "ASSIGNMENT_CREATED", schema = AssignmentCreatedEvent.class),
                @DiscriminatorMapping(value = "DEADLINE_REMINDER", schema = DeadlineReminderEvent.class),
                @DiscriminatorMapping(value = "WORK_ACCEPTED", schema = WorkAcceptedEvent.class),
                @DiscriminatorMapping(value = "WORK_RETURNED", schema = WorkReturnedEvent.class),
                @DiscriminatorMapping(value = "POINTS_AWARDED", schema = PointsAwardedEvent.class),
                @DiscriminatorMapping(value = "ACTIVITY_CREATED", schema = ActivityCreatedEvent.class),
                @DiscriminatorMapping(value = "WORK_SUBMITTED", schema = WorkSubmittedEvent.class),
                @DiscriminatorMapping(value = "POLL_CREATED", schema = PollCreatedEvent.class),
                @DiscriminatorMapping(value = "POLL_CLOSED", schema = PollClosedEvent.class)
            })
    public sealed interface PersonalFeedItem permits AssignmentCreatedEvent, DeadlineReminderEvent, WorkAcceptedEvent,
            WorkReturnedEvent, PointsAwardedEvent, ActivityCreatedEvent, WorkSubmittedEvent, PollCreatedEvent, PollClosedEvent {}

    @Schema(requiredProperties = {"id", "kind", "createdAt", "assignmentId", "checkpointTitle", "deadline"})
    public record AssignmentCreatedEvent(@Min(1) long id, @Schema(_const = "ASSIGNMENT_CREATED") String kind, Instant createdAt,
            @Min(1) long assignmentId, String checkpointTitle, Instant deadline) implements PersonalFeedItem {}

    @Schema(requiredProperties = {"id", "kind", "createdAt", "assignmentId", "checkpointTitle", "deadline"})
    public record DeadlineReminderEvent(@Min(1) long id, @Schema(_const = "DEADLINE_REMINDER") String kind, Instant createdAt,
            @Min(1) long assignmentId, String checkpointTitle, Instant deadline) implements PersonalFeedItem {}

    @Schema(requiredProperties = {"id", "kind", "createdAt", "assignmentId", "checkpointTitle"})
    public record WorkAcceptedEvent(@Min(1) long id, @Schema(_const = "WORK_ACCEPTED") String kind, Instant createdAt,
            @Min(1) long assignmentId, String checkpointTitle) implements PersonalFeedItem {}

    @Schema(requiredProperties = {"id", "kind", "createdAt", "assignmentId", "checkpointTitle"})
    public record WorkReturnedEvent(@Min(1) long id, @Schema(_const = "WORK_RETURNED") String kind, Instant createdAt,
            @Min(1) long assignmentId, String checkpointTitle) implements PersonalFeedItem {}

    @Schema(requiredProperties = {"id", "kind", "createdAt", "activityId", "activityTitle", "amount", "currency"})
    public record PointsAwardedEvent(@Min(1) long id, @Schema(_const = "POINTS_AWARDED") String kind, Instant createdAt,
            @Min(1) long activityId, String activityTitle, @Min(1) int amount, Currency currency) implements PersonalFeedItem {}

    @Schema(requiredProperties = {"id", "kind", "createdAt", "activityId", "activityTitle"})
    public record ActivityCreatedEvent(@Min(1) long id, @Schema(_const = "ACTIVITY_CREATED") String kind, Instant createdAt,
            @Min(1) long activityId, String activityTitle) implements PersonalFeedItem {}

    @Schema(requiredProperties = {"id", "kind", "createdAt", "checkpointId", "checkpointTitle", "studentFullName"})
    public record WorkSubmittedEvent(@Min(1) long id, @Schema(_const = "WORK_SUBMITTED") String kind, Instant createdAt,
            @Min(1) long checkpointId, String checkpointTitle, String studentFullName) implements PersonalFeedItem {}

    @Schema(requiredProperties = {"id", "kind", "createdAt", "pollId", "pollTitle", "endsAt"})
    public record PollCreatedEvent(@Min(1) long id, @Schema(_const = "POLL_CREATED") String kind, Instant createdAt,
            @Min(1) long pollId, String pollTitle, Instant endsAt) implements PersonalFeedItem {}

    @Schema(requiredProperties = {"id", "kind", "createdAt", "pollId", "pollTitle", "voted", "eligible"})
    public record PollClosedEvent(@Min(1) long id, @Schema(_const = "POLL_CLOSED") String kind, Instant createdAt,
            @Min(1) long pollId, String pollTitle, @Min(0) int voted, @Min(0) int eligible) implements PersonalFeedItem {}

    @Schema(requiredProperties = {"items", "nextCursor"})
    public record PersonalFeedPage(List<PersonalFeedItem> items,
            @Schema(types = {"string", "null"}) @Nullable String nextCursor) {}

    @GetMapping("/api/v1/feed/personal")
    public PersonalFeedPage listPersonalFeed(
            @Parameter(schema = @Schema(minLength = 1)) @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.list(cursor, limit);
    }
}

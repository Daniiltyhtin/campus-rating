package ru.campusrating.activity.api;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.activity.domain.ActivityType;
import ru.campusrating.activity.internal.ActivityService;
import ru.campusrating.ledger.Currency;

@RestController
@RequestMapping("/api/v1/activities")
public class ActivityController {
    private final ActivityService service;

    public ActivityController(ActivityService service) {
        this.service = service;
    }

    @Schema(requiredProperties = {"title", "description", "type", "groupIds", "startsAt", "maxPoints"})
    public record ActivityCreate(@NotNull @Size(min = 1, max = 200) String title,
            @NotNull @Size(max = 10000) String description, @NotNull ActivityType type,
            @NotEmpty @ArraySchema(minItems = 1, uniqueItems = true) List<@NotNull @Min(1) Long> groupIds,
            @NotNull Instant startsAt, @NotNull @Min(2) @Max(10000) Integer maxPoints) {}

    @Schema(requiredProperties = {"id", "code", "name"})
    public record FacultyRef(@Min(1) long id, String code, String name) {}

    @Schema(requiredProperties = {"id", "name", "faculty"})
    public record StudyGroupRef(@Min(1) long id, String name, FacultyRef faculty) {}

    @Schema(requiredProperties = {"id", "fullName"})
    public record UserRef(@Min(1) long id, String fullName) {}

    @Schema(requiredProperties = {"level", "amount"})
    public record MyAward(@Schema(type = "integer", format = "int32", allowableValues = {"25", "50", "75", "100"}) int level, int amount) {}

    @Schema(requiredProperties = {"id", "title", "description", "type", "currency", "startsAt", "maxPoints", "groups", "author",
            "enrollmentOpen", "confirmationOpen", "enrolledCount", "confirmedCount", "enrolled", "myAward"})
    public record Activity(@Min(1) long id, String title, String description, ActivityType type, Currency currency,
            Instant startsAt, int maxPoints, List<StudyGroupRef> groups, UserRef author,
            boolean enrollmentOpen, boolean confirmationOpen, int enrolledCount, int confirmedCount,
            @Schema(types = {"boolean", "null"}) @Nullable Boolean enrolled,
            @Nullable MyAward myAward) {}

    @Schema(requiredProperties = "items")
    public record ActivityList(List<Activity> items) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Activity createActivity(@Valid @RequestBody ActivityCreate request) {
        return service.create(request.title(), request.description(), request.type(), request.groupIds(), request.startsAt(),
                request.maxPoints());
    }

    @GetMapping
    public ActivityList listActivities() {
        return new ActivityList(service.list());
    }

    @GetMapping("/{activityId}")
    public Activity getActivity(@PathVariable @Min(1) long activityId) {
        return service.get(activityId);
    }

    @PutMapping("/{activityId}/enrollment")
    public Activity enrollInActivity(@PathVariable @Min(1) long activityId) {
        return service.enrollment(activityId, true);
    }

    @DeleteMapping("/{activityId}/enrollment")
    public Activity cancelEnrollment(@PathVariable @Min(1) long activityId) {
        return service.enrollment(activityId, false);
    }
}

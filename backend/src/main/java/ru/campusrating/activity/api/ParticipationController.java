package ru.campusrating.activity.api;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.activity.api.ActivityController.StudyGroupRef;
import ru.campusrating.activity.api.ActivityController.UserRef;
import ru.campusrating.activity.domain.ActivityType;
import ru.campusrating.activity.domain.ParticipationLevel;
import ru.campusrating.activity.internal.ParticipationService;
import ru.campusrating.activity.internal.ParticipationService.Item;
import ru.campusrating.ledger.Currency;
import ru.campusrating.shared.error.FieldError;
import ru.campusrating.shared.error.ValidationFailedException;

@RestController
@RequestMapping("/api/v1/activities/{activityId}")
public class ParticipationController {
    private final ParticipationService service;

    public ParticipationController(ParticipationService service) {
        this.service = service;
    }

    @Schema(requiredProperties = {"studentId", "level"})
    public record ParticipationItem(@NotNull @Min(1) Long studentId, @NotNull Integer level) {}

    @Schema(requiredProperties = "items")
    public record ParticipationRequest(
            @NotNull @Size(min = 1, max = 500) @ArraySchema(minItems = 1, maxItems = 500)
            List<@Valid @NotNull ParticipationItem> items) {}

    @Schema(requiredProperties = {"level", "amount", "confirmedAt"})
    public record ConfirmedParticipation(int level, int amount, Instant confirmedAt) {}

    @Schema(requiredProperties = {"student", "group", "enrolled", "confirmed"})
    public record Participant(UserRef student, StudyGroupRef group,
            @Schema(types = {"boolean", "null"}) @Nullable Boolean enrolled,
            @Nullable ConfirmedParticipation confirmed) {}

    @Schema(requiredProperties = {"activityId", "type", "maxPoints", "items"})
    public record ParticipantList(@Min(1) long activityId, ActivityType type, int maxPoints, List<Participant> items) {}

    @Schema(requiredProperties = {"studentId", "groupId", "level", "amount"})
    public record AwardLine(@Min(1) long studentId, @Min(1) long groupId, int level, int amount) {}

    @Schema(name = "BudgetCheck", requiredProperties = {"groupId", "groupName", "needed", "remaining", "shortfall"})
    public record GroupBudgetCheck(@Min(1) long groupId, String groupName, int needed, int remaining, @Min(0) int shortfall) {}

    @Schema(requiredProperties = {"currency", "awards", "alreadyConfirmed", "total", "budgets", "withinBudget"})
    public record ParticipationPreview(Currency currency, List<AwardLine> awards, List<AwardLine> alreadyConfirmed, int total,
            List<GroupBudgetCheck> budgets, boolean withinBudget) {}

    @Schema(requiredProperties = {"currency", "awards", "alreadyConfirmed", "total"})
    public record ParticipationResult(Currency currency, List<AwardLine> awards, List<AwardLine> alreadyConfirmed, int total) {}

    @GetMapping("/participants")
    public ParticipantList listActivityParticipants(@PathVariable @Min(1) long activityId) {
        return service.participants(activityId);
    }

    @PostMapping("/participation/preview")
    public ParticipationPreview previewParticipation(@PathVariable @Min(1) long activityId,
            @Valid @RequestBody ParticipationRequest request) {
        return service.preview(activityId, items(request));
    }

    @PostMapping("/participation")
    public ParticipationResult confirmParticipation(@PathVariable @Min(1) long activityId,
            @Valid @RequestBody ParticipationRequest request) {
        return service.confirm(activityId, items(request));
    }

    private List<Item> items(ParticipationRequest request) {
        var items = new ArrayList<Item>();
        for (int index = 0; index < request.items().size(); index++) {
            var item = request.items().get(index);
            items.add(new Item(item.studentId(), level(item.level(), index)));
        }
        return items;
    }

    private ParticipationLevel level(int percent, int index) {
        try {
            return ParticipationLevel.of(percent);
        } catch (IllegalArgumentException exception) {
            var error = new FieldError("items[" + index + "].level", "INVALID", exception.getMessage());
            throw new ValidationFailedException("Проверьте поля запроса", List.of(error));
        }
    }
}

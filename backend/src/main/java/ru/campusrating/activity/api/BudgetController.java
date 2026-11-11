package ru.campusrating.activity.api;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.activity.api.ActivityController.StudyGroupRef;
import ru.campusrating.activity.api.ActivityController.UserRef;
import ru.campusrating.activity.domain.ActivityType;
import ru.campusrating.activity.internal.BudgetService;
import ru.campusrating.ledger.Currency;

@RestController
public class BudgetController {
    private static final String MONTH = "^\\d{4}-(0[1-9]|1[0-2])$";

    private final BudgetService service;

    public BudgetController(BudgetService service) {
        this.service = service;
    }

    @Schema(requiredProperties = {"group", "limit", "spent", "remaining"})
    public record GroupBudget(StudyGroupRef group, int limit, int spent, int remaining) {}

    @Schema(requiredProperties = {"month", "items"})
    public record BudgetList(@Schema(pattern = MONTH) String month, List<GroupBudget> items) {}

    @Schema(requiredProperties = {"id", "title", "type"})
    public record ActivityRef(@Min(1) long id, String title, ActivityType type) {}

    @Schema(requiredProperties = {"id", "activity", "student", "group", "level", "amount", "currency", "confirmedAt"})
    public record TeacherAward(@Min(1) long id, ActivityRef activity, UserRef student, StudyGroupRef group, int level, int amount,
            Currency currency, Instant confirmedAt) {}

    @Schema(requiredProperties = {"items", "nextCursor"})
    public record TeacherAwardPage(List<TeacherAward> items, @Schema(types = {"string", "null"}) @Nullable String nextCursor) {}

    @Schema(requiredProperties = {"group", "currency", "amount", "count"})
    public record AwardSummaryLine(StudyGroupRef group, Currency currency, int amount, int count) {}

    @Schema(requiredProperties = {"month", "total", "count", "items"})
    public record TeacherAwardSummary(@Schema(pattern = MONTH) String month, int total, int count, List<AwardSummaryLine> items) {}

    @GetMapping("/api/v1/budgets")
    public BudgetList listBudgets(@RequestParam(required = false) @Pattern(regexp = MONTH) @Nullable String month) {
        return service.budgets(month);
    }

    @GetMapping("/api/v1/me/awards")
    public TeacherAwardPage listMyAwards(
            @RequestParam(required = false) @Min(1) @Nullable Long groupId,
            @RequestParam(required = false) @Pattern(regexp = MONTH) @Nullable String month,
            @Parameter(schema = @Schema(minLength = 1)) @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.awards(groupId, month, cursor, limit);
    }

    @GetMapping("/api/v1/me/awards/summary")
    public TeacherAwardSummary getMyAwardsSummary(@RequestParam(required = false) @Pattern(regexp = MONTH) @Nullable String month) {
        return service.summary(month);
    }
}

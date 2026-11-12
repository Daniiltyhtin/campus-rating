package ru.campusrating.analytics.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.analytics.internal.GroupAnalyticsService;

@RestController
public class GroupAnalyticsController {
    @Schema(requiredProperties = {"id", "fullName"})
    public record UserRef(@Min(1) long id, String fullName) {}
    @Schema(requiredProperties = {"id", "code", "name"})
    public record FacultyRef(@Min(1) long id, String code, String name) {}
    @Schema(requiredProperties = {"id", "name", "faculty"})
    public record StudyGroupRef(@Min(1) long id, String name, FacultyRef faculty) {}
    @Schema(requiredProperties = {"date", "averageAr", "students"})
    public record AnalyticsDay(LocalDate date, @Nullable BigDecimal averageAr, @Min(0) int students) {}
    @Schema(requiredProperties = {"student", "bua", "boa", "ar"})
    public record AnalyticsPoints(UserRef student, int bua, int boa, int ar) {}
    @Schema(requiredProperties = {"group", "semesterStart", "days", "items"})
    public record GroupAnalytics(StudyGroupRef group, @Nullable LocalDate semesterStart,
            List<AnalyticsDay> days, List<AnalyticsPoints> items) {}
    @Schema(requiredProperties = {"month", "teacher", "limit", "spent", "remaining", "awards", "bua", "boa"})
    public record AnalyticsBudgetMonth(@Schema(pattern = "^\\d{4}-\\d{2}$") String month, @Nullable UserRef teacher,
            int limit, int spent, @Nullable Integer remaining, int awards, int bua, int boa) {}
    @Schema(requiredProperties = {"items"})
    public record GroupAnalyticsBudget(List<AnalyticsBudgetMonth> items) {}

    private final GroupAnalyticsService service;

    public GroupAnalyticsController(GroupAnalyticsService service) { this.service = service; }

    @GetMapping("/api/v1/groups/{groupId}/analytics")
    public GroupAnalytics getGroupAnalytics(@PathVariable @Min(1) long groupId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return service.get(groupId, from, to);
    }

    @GetMapping("/api/v1/groups/{groupId}/analytics/budget")
    public GroupAnalyticsBudget getGroupAnalyticsBudget(@PathVariable @Min(1) long groupId) {
        return service.budget(groupId);
    }
}

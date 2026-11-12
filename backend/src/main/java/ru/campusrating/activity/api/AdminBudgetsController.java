package ru.campusrating.activity.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import ru.campusrating.activity.internal.AdminBudgetService;
import ru.campusrating.identity.UserRef;
import ru.campusrating.activity.api.ActivityController.StudyGroupRef;

@RestController
@RequestMapping("/api/v1/admin/budgets")
public class AdminBudgetsController {
    private final AdminBudgetService service;
    public AdminBudgetsController(AdminBudgetService service) { this.service=service; }
    @GetMapping public AdminBudgetList listAdminBudgets(@RequestParam(required=false) String month) { return new AdminBudgetList(service.list(month)); }
    @GetMapping("/impact") public BudgetImpact getAdminBudgetImpact(@RequestParam @Min(0) @Max(100000) int limit) { return new BudgetImpact(service.impact(limit)); }
    @Schema(requiredProperties="items") public record AdminBudgetList(List<AdminBudget> items) {}
    @Schema(requiredProperties={"teacher","group","limit","spent","remaining"})
    public record AdminBudget(UserRef teacher,StudyGroupRef group,int limit,int spent,int remaining) {}
    @Schema(requiredProperties="count") public record BudgetImpact(int count) {}
}

package ru.campusrating.settings.api;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import ru.campusrating.settings.internal.AdminSettingsService;

@RestController
@RequestMapping("/api/v1/admin/settings")
public class AdminSettingsController {
    private final AdminSettingsService service;
    public AdminSettingsController(AdminSettingsService service) { this.service=service; }
    @GetMapping public AdminSettings getAdminSettings() { return service.read(); }
    @PutMapping public AdminSettings updateAdminSettings(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=AdminSettingsRequest.class)))
            @RequestBody Map<String,Object> body) { return service.save(body); }
    @Schema(requiredProperties={"penaltyStep","penaltyIntervalSeconds","minPenaltyIntervalSeconds","semesterAccrual","monthlyBudgetLimit"})
    public record AdminSettings(int penaltyStep,int penaltyIntervalSeconds,int minPenaltyIntervalSeconds,int semesterAccrual,int monthlyBudgetLimit) {}
    @Schema(requiredProperties={"penaltyStep","penaltyIntervalSeconds","semesterAccrual","monthlyBudgetLimit"})
    public record AdminSettingsRequest(int penaltyStep,int penaltyIntervalSeconds,int semesterAccrual,int monthlyBudgetLimit) {}
}

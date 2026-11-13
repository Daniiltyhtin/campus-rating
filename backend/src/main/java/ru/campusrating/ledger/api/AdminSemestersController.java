package ru.campusrating.ledger.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.campusrating.ledger.internal.AdminSemesterService;

@RestController
@RequestMapping("/api/v1/admin/semesters")
public class AdminSemestersController {
    private final AdminSemesterService service;
    public AdminSemestersController(AdminSemesterService service) { this.service = service; }
    @GetMapping public AdminSemesterList listAdminSemesters() { return new AdminSemesterList(service.list()); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public AdminSemester createAdminSemester(@Valid @RequestBody AdminSemesterRequest body) { return service.save(null, body); }
    @PutMapping("/{semesterId}")
    public AdminSemester updateAdminSemester(@PathVariable long semesterId, @Valid @RequestBody AdminSemesterRequest body) { return service.save(semesterId, body); }
    @DeleteMapping("/{semesterId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAdminSemester(@PathVariable long semesterId) { service.delete(semesterId); }
    @PostMapping("/{semesterId}/start")
    public AdminSemester startAdminSemester(@PathVariable long semesterId) { return service.start(semesterId); }
    @Schema(requiredProperties={"id","title","startsAt","started","accrual"})
    public record AdminSemester(@Min(1) long id, String title, Instant startsAt, boolean started,
            @Schema(types={"integer","null"}, format="int32") Integer accrual) {}
    @Schema(requiredProperties="items") public record AdminSemesterList(List<AdminSemester> items) {}
    @Schema(requiredProperties={"title","startsAt"})
    public record AdminSemesterRequest(@NotBlank @Size(max=100) String title, @NotNull Instant startsAt) {
        public AdminSemesterRequest { title = title == null ? null : title.strip(); }
    }
}

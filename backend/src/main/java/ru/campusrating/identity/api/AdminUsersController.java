package ru.campusrating.identity.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.campusrating.directory.DepartmentInfo;
import ru.campusrating.identity.GroupMembershipState;
import ru.campusrating.identity.Role;
import ru.campusrating.identity.UserRef;
import ru.campusrating.identity.api.AuthDtos.StudyGroupRef;
import ru.campusrating.identity.internal.AdminUserService;

@RestController
public class AdminUsersController {
    private final AdminUserService service;
    public AdminUsersController(AdminUserService service) { this.service=service; }
    @GetMapping("/api/v1/admin/users")
    public AdminUserPage listAdminUsers(@RequestParam(required=false) String search,@RequestParam(required=false) Role role,@RequestParam(required=false) UserStatus status,@RequestParam(defaultValue="name") UserSort sort,@RequestParam(required=false) SortDirection direction,@RequestParam(required=false) String cursor) { return service.list(search,role,status,sort,direction,cursor); }
    @GetMapping("/api/v1/groups/{groupId}/students")
    public GroupStudentList listGroupStudents(@PathVariable long groupId) { return new GroupStudentList(service.students(groupId)); }
    @PostMapping("/api/v1/users/{userId}/temporary-password")
    public ResponseEntity<TemporaryPasswordResult> setTemporaryPassword(@PathVariable long userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new TemporaryPasswordResult(service.reset(userId)));
    }
    @GetMapping("/api/v1/me/password-audit")
    @ApiResponse(responseCode="200",description="Операция выполнена.")
    @ApiResponse(responseCode="204",description="Временный пароль ещё не выставлялся.")
    public ResponseEntity<PasswordAudit> getOwnPasswordAudit() {
        return service.audit().map(a->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(a))
                .orElseGet(()->ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
    }
    public enum UserStatus { PENDING, APPROVED, MEMBERSHIP_PENDING, MEMBERSHIP_REJECTED, MEMBERSHIP_ACTIVE }
    public enum UserSort { name, email, role, unit, status, created, lastLogin }
    public enum SortDirection { asc, desc }
    @Schema(requiredProperties={"id","fullName","email","role","group","department","passwordSetAt","status","createdAt","lastLoginAt"})
    public record AdminUser(@Min(1) long id,String fullName,@Schema(format="email") String email,Role role,StudyGroupRef group,DepartmentInfo department,
            @Schema(types={"string","null"},format="date-time") Instant passwordSetAt,
            @Schema(types={"string","null"},allowableValues={"PENDING","APPROVED","MEMBERSHIP_PENDING","MEMBERSHIP_REJECTED","MEMBERSHIP_ACTIVE"}) UserStatus status,
            Instant createdAt,@Schema(types={"string","null"},format="date-time") Instant lastLoginAt) {}
    @Schema(requiredProperties={"items","nextCursor"})
    public record AdminUserPage(List<AdminUser> items,@Schema(types={"string","null"}) String nextCursor) {}
    @Schema(requiredProperties={"id","fullName","email","membershipState","passwordSetAt"})
    public record GroupStudent(@Min(1) long id,String fullName,@Schema(format="email") String email,GroupMembershipState membershipState,
            @Schema(types={"string","null"},format="date-time") Instant passwordSetAt) {}
    @Schema(requiredProperties="items") public record GroupStudentList(List<GroupStudent> items) {}
    @Schema(requiredProperties="password") public record TemporaryPasswordResult(@Schema(minLength=12,maxLength=12) String password) {}
    @Schema(requiredProperties={"actor","createdAt"}) public record PasswordAudit(UserRef actor,Instant createdAt) {}
}

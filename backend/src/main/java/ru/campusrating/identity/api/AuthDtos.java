package ru.campusrating.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import ru.campusrating.directory.DepartmentInfo;
import ru.campusrating.directory.FacultyInfo;
import ru.campusrating.identity.GroupMembershipState;
import ru.campusrating.shared.mail.MailModeProperties;

public final class AuthDtos {
    private AuthDtos() {}
    @Schema(requiredProperties = {"email", "password"})
    public record LoginRequest(@NotNull @Email @Size(max = 254) String email,
                               @NotNull @Size(min = 1, max = 1024) String password) {}
    @Schema(requiredProperties = {"fullName", "email", "password", "facultyId", "groupId"})
    public record StudentRegistration(@NotNull @Size(min = 1, max = 200) String fullName,
            @NotNull @Email @Size(max = 254) String email, @NotNull @Size(min = 8, max = 72) String password,
            @NotNull @Min(1) Long facultyId, @NotNull @Min(1) Long groupId) {}
    @Schema(requiredProperties = {"fullName", "email", "password", "departmentId"})
    public record TeacherRegistration(@NotNull @Size(min = 1, max = 200) String fullName,
            @NotNull @Email @Size(max = 254) String email, @NotNull @Size(min = 8, max = 72) String password,
            @NotNull @Min(1) Long departmentId) {}
    @Schema(requiredProperties = {"currentPassword", "newPassword"})
    public record PasswordChange(@NotNull @Size(min = 1, max = 1024) String currentPassword,
                                  @NotNull @Size(min = 8, max = 72) String newPassword) {}
    public enum NextStep { LOGIN, CONFIRM_EMAIL }
    @Schema(requiredProperties = {"userId", "nextStep"})
    public record RegistrationResult(@Min(1) long userId, NextStep nextStep) {}
    @Schema(requiredProperties = {"demo", "emailVerification"})
    public record AuthConfig(boolean demo, MailModeProperties.EmailVerification emailVerification) {}
    @Schema(requiredProperties = {"id", "name", "faculty"})
    public record StudyGroupRef(@Min(1) long id, String name, FacultyInfo faculty) {}
    @Schema(requiredProperties = "items")
    public record StudyGroupList(List<StudyGroupRef> items) {}

    @Schema(name = "CurrentUser", oneOf = {CurrentStudent.class, CurrentTeacher.class, CurrentAdmin.class},
            discriminatorProperty = "role", discriminatorMapping = {
                @DiscriminatorMapping(value = "STUDENT", schema = CurrentStudent.class),
                @DiscriminatorMapping(value = "TEACHER", schema = CurrentTeacher.class),
                @DiscriminatorMapping(value = "ADMIN", schema = CurrentAdmin.class)})
    public sealed interface SessionView permits CurrentStudent, CurrentTeacher, CurrentAdmin {}

    @Schema(requiredProperties = {"role", "id", "fullName", "email", "mustChangePassword", "group", "membershipState"})
    public record CurrentStudent(@Min(1) long id, String fullName, @Schema(format = "email", maxLength = 254) String email,
            boolean mustChangePassword, StudyGroupRef group, GroupMembershipState membershipState) implements SessionView {
        @JsonProperty("role") @Schema(type = "string", _const = "STUDENT") public String role() { return "STUDENT"; }
    }
    @Schema(requiredProperties = {"role", "id", "fullName", "email", "mustChangePassword", "department", "teacherApproved"})
    public record CurrentTeacher(@Min(1) long id, String fullName, @Schema(format = "email", maxLength = 254) String email,
            boolean mustChangePassword, DepartmentInfo department, boolean teacherApproved) implements SessionView {
        @JsonProperty("role") @Schema(type = "string", _const = "TEACHER") public String role() { return "TEACHER"; }
    }
    @Schema(requiredProperties = {"role", "id", "fullName", "email", "mustChangePassword"})
    public record CurrentAdmin(@Min(1) long id, String fullName, @Schema(format = "email", maxLength = 254) String email,
            boolean mustChangePassword) implements SessionView {
        @JsonProperty("role") @Schema(type = "string", _const = "ADMIN") public String role() { return "ADMIN"; }
    }
}

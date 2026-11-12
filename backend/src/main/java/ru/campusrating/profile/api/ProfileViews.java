package ru.campusrating.profile.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import ru.campusrating.checkpoint.AssignmentStatus;
import ru.campusrating.directory.DepartmentInfo;
import ru.campusrating.directory.FacultyInfo;

/** Ответ getUserProfile: схемы UserProfile и ProfileTask контракта. */
public final class ProfileViews {
    private ProfileViews() {}

    @Schema(name = "UserProfile", oneOf = {StudentProfile.class, TeacherProfile.class, AdminProfile.class},
            discriminatorProperty = "role", discriminatorMapping = {
                @DiscriminatorMapping(value = "STUDENT", schema = StudentProfile.class),
                @DiscriminatorMapping(value = "TEACHER", schema = TeacherProfile.class),
                @DiscriminatorMapping(value = "ADMIN", schema = AdminProfile.class)})
    public sealed interface UserProfile permits StudentProfile, TeacherProfile, AdminProfile {}

    @Schema(name = "StudentProfile", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"role", "id", "fullName", "group", "balances", "activeTasks", "completedTasks"})
    public record StudentProfile(@Min(1) long id, String fullName, ProfileStudyGroup group, ProfileBalances balances,
            @Schema(description = "`OPEN` и `SUBMITTED`, сначала `OVERDUE`, затем по дедлайну.") List<ProfileTask> activeTasks,
            @Schema(description = "Все `ACCEPTED`, от недавно принятых.") List<ProfileTask> completedTasks)
            implements UserProfile {
        @JsonProperty("role") @Schema(type = "string", _const = "STUDENT") public String role() { return "STUDENT"; }
    }

    @Schema(name = "TeacherProfile", requiredProperties = {"role", "id", "fullName", "department"})
    public record TeacherProfile(@Min(1) long id, String fullName, DepartmentInfo department) implements UserProfile {
        @JsonProperty("role") @Schema(type = "string", _const = "TEACHER") public String role() { return "TEACHER"; }
    }

    @Schema(name = "AdminProfile", requiredProperties = {"role", "id", "fullName"})
    public record AdminProfile(@Min(1) long id, String fullName) implements UserProfile {
        @JsonProperty("role") @Schema(type = "string", _const = "ADMIN") public String role() { return "ADMIN"; }
    }

    @Schema(name = "ProfileStudyGroup", requiredProperties = {"id", "name", "faculty"})
    public record ProfileStudyGroup(@Min(1) long id, String name, FacultyInfo faculty) {}

    @Schema(name = "ProfileBalances", requiredProperties = {"AR", "BUA", "BOA"})
    public record ProfileBalances(@JsonProperty("AR") @Min(0) @Max(1000) int ar,
                                  @JsonProperty("BUA") @Min(0) int bua,
                                  @JsonProperty("BOA") @Min(0) int boa) {}

    @Schema(name = "ProfileTask", oneOf = {PublicProfileTask.class, OwnProfileTask.class, AdminProfileTask.class},
            discriminatorProperty = "view", discriminatorMapping = {
                @DiscriminatorMapping(value = "PUBLIC", schema = PublicProfileTask.class),
                @DiscriminatorMapping(value = "OWN", schema = OwnProfileTask.class),
                @DiscriminatorMapping(value = "ADMIN", schema = AdminProfileTask.class)})
    public sealed interface ProfileTask permits PublicProfileTask, OwnProfileTask, AdminProfileTask {}

    @Schema(name = "PublicProfileTask", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            description = "Задача в чужом профиле — без перехода к сдаче.",
            requiredProperties = {"view", "title", "deadline", "status", "charged"})
    public record PublicProfileTask(String title, Instant deadline, AssignmentStatus status, int charged)
            implements ProfileTask {
        @JsonProperty("view") @Schema(type = "string", _const = "PUBLIC") public String view() { return "PUBLIC"; }
    }

    @Schema(name = "OwnProfileTask", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            description = "Задача в своём профиле — с переходом в задачу `/tasks/:assignmentId`.",
            requiredProperties = {"view", "assignmentId", "title", "deadline", "status", "charged"})
    public record OwnProfileTask(@Min(1) long assignmentId, String title, Instant deadline, AssignmentStatus status,
                                 int charged) implements ProfileTask {
        @JsonProperty("view") @Schema(type = "string", _const = "OWN") public String view() { return "OWN"; }
    }

    @Schema(name = "AdminProfileTask", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            description = "Задача для администратора — со ссылкой «Материалы сдачи» `/admin/submissions/:assignmentId`.",
            requiredProperties = {"view", "assignmentId", "title", "deadline", "status", "charged"})
    public record AdminProfileTask(@Min(1) long assignmentId, String title, Instant deadline, AssignmentStatus status,
                                   int charged) implements ProfileTask {
        @JsonProperty("view") @Schema(type = "string", _const = "ADMIN") public String view() { return "ADMIN"; }
    }
}

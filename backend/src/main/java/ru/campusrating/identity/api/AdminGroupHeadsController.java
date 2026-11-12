package ru.campusrating.identity.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.identity.internal.GroupHeads;

@RestController
class AdminGroupHeadsController {
    private final GroupHeads heads;
    AdminGroupHeadsController(GroupHeads heads) { this.heads = heads; }

    @GetMapping("/api/v1/admin/group-heads")
    GroupHeadList listGroupHeads() {
        return new GroupHeadList(heads.list().stream().map(head -> new GroupHead(head.groupId(), HeadStudent.of(head.student()))).toList());
    }
    @PutMapping("/api/v1/admin/group-heads/{groupId}")
    GroupHead assignGroupHead(@PathVariable @Min(1) long groupId, @Valid @RequestBody GroupHeadAssignment body) {
        var assigned = heads.assign(groupId, body.studentId());
        return new GroupHead(assigned.groupId(), HeadStudent.of(assigned.student()));
    }
    @DeleteMapping("/api/v1/admin/group-heads/{groupId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeGroupHead(@PathVariable @Min(1) long groupId) { heads.remove(groupId); }

    @Schema(requiredProperties = "studentId") record GroupHeadAssignment(@NotNull @Min(1) Long studentId) {}
    @Schema(name = "UserRef", requiredProperties = {"id", "fullName"})
    record HeadStudent(@Min(1) long id, String fullName) {
        static HeadStudent of(ru.campusrating.identity.UserRef user) { return new HeadStudent(user.id(), user.fullName()); }
    }
    @Schema(requiredProperties = {"groupId", "student"}) record GroupHead(@Min(1) long groupId, HeadStudent student) {}
    @Schema(requiredProperties = "items") record GroupHeadList(List<GroupHead> items) {}
}

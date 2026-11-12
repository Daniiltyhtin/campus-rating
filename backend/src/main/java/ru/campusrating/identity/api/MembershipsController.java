package ru.campusrating.identity.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.identity.api.AuthDtos.StudyGroupRef;
import ru.campusrating.identity.internal.MembershipApproval;

@RestController
class MembershipsController {
    private final MembershipApproval approval;
    MembershipsController(MembershipApproval approval) { this.approval = approval; }

    @GetMapping("/api/v1/memberships/pending")
    PendingMembershipList listPendingMemberships() {
        return new PendingMembershipList(approval.pending().stream().map(item -> new PendingMembership(item.studentId(), item.fullName(),
                item.email(), new StudyGroupRef(item.group().id(), item.group().name(), item.group().faculty()), item.requestedAt())).toList());
    }
    @PostMapping("/api/v1/memberships/{studentId}/approval") @ResponseStatus(HttpStatus.NO_CONTENT)
    void approveMembership(@PathVariable @Min(1) long studentId) { approval.approve(studentId); }
    @PostMapping("/api/v1/memberships/{studentId}/rejection") @ResponseStatus(HttpStatus.NO_CONTENT)
    void rejectMembership(@PathVariable @Min(1) long studentId) { approval.reject(studentId); }

    @Schema(requiredProperties = {"studentId", "fullName", "email", "group", "requestedAt"})
    record PendingMembership(@Min(1) long studentId, String fullName, @Schema(format = "email", maxLength = 254) String email,
                             StudyGroupRef group, Instant requestedAt) {}
    @Schema(requiredProperties = "items") record PendingMembershipList(List<PendingMembership> items) {}
}

package ru.campusrating.checkpoint.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import ru.campusrating.identity.GroupMembershipState;

@Schema(requiredProperties = {"items", "nextCursor", "membershipState"})
public record StudentTaskPage(
        List<StudentTask> items,
        @Schema(types = {"string", "null"}) String nextCursor,
        GroupMembershipState membershipState) {}

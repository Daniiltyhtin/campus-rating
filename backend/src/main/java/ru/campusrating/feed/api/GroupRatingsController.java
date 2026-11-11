package ru.campusrating.feed.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.feed.api.AwardsFeedController.UserRef;
import ru.campusrating.feed.internal.GroupRatingService;
import ru.campusrating.ledger.Currency;

@RestController
public class GroupRatingsController {
    private final GroupRatingService service;

    public GroupRatingsController(GroupRatingService service) {
        this.service = service;
    }

    @Schema(requiredProperties = {"id", "code", "name"})
    public record FacultyRef(@Min(1) long id, String code, String name) {}

    @Schema(requiredProperties = {"id", "name", "faculty"})
    public record StudyGroupRef(@Min(1) long id, String name, FacultyRef faculty) {}

    @Schema(requiredProperties = {"AR", "BUA", "BOA"})
    public record Balances(@JsonProperty("AR") @Min(0) @Max(1000) int ar,
            @JsonProperty("BUA") @Min(0) int bua, @JsonProperty("BOA") @Min(0) int boa) {}

    @Schema(requiredProperties = {"student", "balances"})
    public record RatingRow(UserRef student, Balances balances) {}

    @Schema(requiredProperties = {"group", "sort", "items"})
    public record GroupRating(StudyGroupRef group, Currency sort, List<RatingRow> items) {}

    @GetMapping("/api/v1/groups/{groupId}/ratings")
    public GroupRating getGroupRating(@PathVariable @Min(1) long groupId,
            @RequestParam(defaultValue = "AR") Currency sort) {
        return service.get(groupId, sort);
    }
}

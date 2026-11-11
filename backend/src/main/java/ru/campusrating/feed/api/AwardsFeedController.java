package ru.campusrating.feed.api;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.feed.internal.AwardsFeedService;
import ru.campusrating.ledger.Currency;

@RestController
public class AwardsFeedController {
    private final AwardsFeedService service;

    public AwardsFeedController(AwardsFeedService service) {
        this.service = service;
    }

    @Schema(requiredProperties = {"id", "fullName"})
    public record UserRef(@Min(1) long id, String fullName) {}

    @Schema(requiredProperties = {"id", "title"})
    public record ActivityTitle(@Min(1) long id, String title) {}

    @Schema(requiredProperties = {"entryId", "createdAt", "student", "amount", "currency", "activity", "teacher"})
    public record AwardFeedItem(@Min(1) long entryId, Instant createdAt, UserRef student, @Min(1) int amount,
            Currency currency, ActivityTitle activity, UserRef teacher) {}

    @Schema(requiredProperties = {"items", "nextCursor"})
    public record AwardFeedPage(List<AwardFeedItem> items,
            @Schema(types = {"string", "null"}) @Nullable String nextCursor) {}

    @GetMapping("/api/v1/feed/awards")
    public AwardFeedPage listAwardsFeed(
            @Parameter(schema = @Schema(minLength = 1)) @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.list(cursor, limit);
    }
}

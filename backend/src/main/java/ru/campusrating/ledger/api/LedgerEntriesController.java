package ru.campusrating.ledger.api;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.ledger.Currency;
import ru.campusrating.ledger.EntryKind;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.ledger.LedgerEntryView;
import ru.campusrating.ledger.SourceType;
import ru.campusrating.shared.error.FieldError;
import ru.campusrating.shared.error.ValidationFailedException;

/** История проводок для профиля (мастер-R6); пользователей не проверяет — модуль не зависит от identity. */
@RestController
public class LedgerEntriesController {
    private final LedgerApi ledger;

    public LedgerEntriesController(LedgerApi ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/api/v1/ledger/{userId}/entries")
    public LedgerEntryPage listLedgerEntries(
            @PathVariable @Min(1) long userId,
            @Parameter(schema = @Schema(implementation = Currency.class)) @RequestParam(required = false) String currency,
            @Parameter(schema = @Schema(minLength = 1)) @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        var page = ledger.history(userId, currency(currency), Optional.ofNullable(cursor), limit);
        return new LedgerEntryPage(page.items().stream().map(LedgerEntriesController::item).toList(), page.nextCursor());
    }

    private static Optional<Currency> currency(String value) {
        if (value == null) return Optional.empty();
        try {
            return Optional.of(Currency.valueOf(value));
        } catch (IllegalArgumentException exception) {
            throw new ValidationFailedException("Проверьте поля запроса",
                    List.of(new FieldError("currency", "INVALID", "Укажите AR, BUA или BOA")));
        }
    }

    private static LedgerEntryItem item(LedgerEntryView view) {
        var source = view.source();
        var author = view.author();
        return new LedgerEntryItem(view.id(), view.createdAt(), view.currency(), view.amount(), view.kind(),
                new LedgerSourceItem(source.type(), source.id(), source.title()),
                author == null ? null : new LedgerAuthorItem(author.id(), author.fullName()));
    }

    @Schema(name = "LedgerEntryPage", requiredProperties = {"items", "nextCursor"})
    public record LedgerEntryPage(List<LedgerEntryItem> items,
                                  @Schema(types = {"string", "null"}) String nextCursor) {}

    @Schema(name = "LedgerEntryItem", description = "Строка истории — `[Дата] - [+50 БОА] - «Название» от [ФИО]` (мастер-R6).",
            requiredProperties = {"id", "createdAt", "currency", "amount", "kind", "source", "author"})
    public record LedgerEntryItem(@Min(1) long id, Instant createdAt, Currency currency,
                                  @Schema(description = "Знаковая фактическая сумма, не 0.") int amount, EntryKind kind,
                                  LedgerSourceItem source,
                                  @Schema(implementation = NullableAuthor.class) LedgerAuthorItem author) {}

    @Schema(name = "LedgerSourceItem", requiredProperties = {"type", "id", "title"})
    public record LedgerSourceItem(SourceType type,
                                   @Schema(types = {"integer", "null"}, format = "int64") Long id,
                                   String title) {}

    @Schema(name = "UserRef", requiredProperties = {"id", "fullName"})
    public record LedgerAuthorItem(@Min(1) long id, String fullName) {}

    @Schema(anyOf = {LedgerAuthorItem.class, LedgerNull.class})
    public interface NullableAuthor {}

    @Schema(types = "null", schemaResolution = Schema.SchemaResolution.INLINE)
    public interface LedgerNull {}
}

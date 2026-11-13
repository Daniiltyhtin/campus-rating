package ru.campusrating.feed.internal;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import ru.campusrating.feed.api.AwardsFeedController.ActivityTitle;
import ru.campusrating.feed.api.AwardsFeedController.AwardFeedItem;
import ru.campusrating.feed.api.AwardsFeedController.AwardFeedPage;
import ru.campusrating.feed.api.AwardsFeedController.UserRef;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.ledger.AwardEntryView;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.ledger.LedgerPage;
import ru.campusrating.shared.error.FieldError;
import ru.campusrating.shared.error.ValidationFailedException;

@Service
public class AwardsFeedService {
    private final LedgerApi ledger;
    private final GroupMembers members;
    private final CurrentUser current;

    public AwardsFeedService(LedgerApi ledger, GroupMembers members, CurrentUser current) {
        this.ledger = ledger;
        this.members = members;
        this.current = current;
    }

    public AwardFeedPage list(@Nullable String cursor, int limit, boolean mine) {
        var position = Optional.ofNullable(cursor);
        var page = mine ? own(position, limit) : ledger.awards(position, limit);
        var users = members.users(page.items().stream().map(entry -> entry.studentId()).distinct().toList());
        var items = page.items().stream().map(entry -> {
            var student = users.get(entry.studentId());
            return new AwardFeedItem(entry.entryId(), entry.createdAt(), new UserRef(student.id(), student.fullName()),
                    entry.amount(), entry.currency(), new ActivityTitle(entry.source().id(), entry.source().title()),
                    new UserRef(entry.author().id(), entry.author().fullName()));
        }).toList();
        return new AwardFeedPage(items, page.nextCursor());
    }

    private LedgerPage<AwardEntryView> own(Optional<String> position, int limit) {
        var user = current.require();
        return switch (user.role()) {
            case STUDENT -> ledger.awardsToStudent(user.id(), position, limit);
            case TEACHER -> ledger.awardsByAuthor(user.id(), position, limit);
            case ADMIN -> {
                if (position.isPresent()) {
                    throw new ValidationFailedException("Неверный курсор",
                            List.of(new FieldError("cursor", "INVALID", "Неверный формат курсора")));
                }
                yield new LedgerPage<>(List.of(), null);
            }
        };
    }
}

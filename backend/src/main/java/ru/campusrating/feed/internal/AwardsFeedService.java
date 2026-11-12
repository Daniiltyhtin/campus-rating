package ru.campusrating.feed.internal;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import ru.campusrating.feed.api.AwardsFeedController.ActivityTitle;
import ru.campusrating.feed.api.AwardsFeedController.AwardFeedItem;
import ru.campusrating.feed.api.AwardsFeedController.AwardFeedPage;
import ru.campusrating.feed.api.AwardsFeedController.UserRef;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.ledger.LedgerApi;

@Service
public class AwardsFeedService {
    private final LedgerApi ledger;
    private final GroupMembers members;

    public AwardsFeedService(LedgerApi ledger, GroupMembers members) {
        this.ledger = ledger;
        this.members = members;
    }

    public AwardFeedPage list(@Nullable String cursor, int limit) {
        var page = ledger.awards(Optional.ofNullable(cursor), limit);
        var users = members.users(page.items().stream().map(entry -> entry.studentId()).distinct().toList());
        var items = page.items().stream().map(entry -> {
            var student = users.get(entry.studentId());
            return new AwardFeedItem(entry.entryId(), entry.createdAt(), new UserRef(student.id(), student.fullName()),
                    entry.amount(), entry.currency(), new ActivityTitle(entry.source().id(), entry.source().title()),
                    new UserRef(entry.author().id(), entry.author().fullName()));
        }).toList();
        return new AwardFeedPage(items, page.nextCursor());
    }
}

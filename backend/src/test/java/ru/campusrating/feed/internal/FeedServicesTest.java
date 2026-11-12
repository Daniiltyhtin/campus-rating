package ru.campusrating.feed.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.campusrating.identity.AuthenticatedUser;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.GroupMembershipState;
import ru.campusrating.identity.GroupRef;
import ru.campusrating.identity.Role;
import ru.campusrating.identity.StudentMember;
import ru.campusrating.identity.TeacherScope;
import ru.campusrating.identity.UserRef;
import ru.campusrating.ledger.Author;
import ru.campusrating.ledger.AwardEntryView;
import ru.campusrating.ledger.Balances;
import ru.campusrating.ledger.Currency;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.ledger.LedgerPage;
import ru.campusrating.ledger.Source;
import ru.campusrating.ledger.SourceType;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FeedServicesTest {
    final LedgerApi ledger = mock(LedgerApi.class);
    final GroupMembers members = mock(GroupMembers.class);
    final TeacherScope scope = mock(TeacherScope.class);
    final CurrentUser current = mock(CurrentUser.class);

    @Test
    void feedUsesCurrentStudentNamesAndSnapshotsAndPreservesPage() {
        var time = Instant.parse("2026-11-12T12:00:00Z");
        var entry = new AwardEntryView(9, time, 4, 38, Currency.BUA,
                new Source(SourceType.ACTIVITY, 6L, "Снимок активности"), new Author(3, "Снимок автора"));
        when(ledger.awards(Optional.of("page"), 1)).thenReturn(new LedgerPage<>(List.of(entry), "next"));
        when(members.users(List.of(4L))).thenReturn(Map.of(4L, new UserRef(4, "Текущее имя")));

        var page = new AwardsFeedService(ledger, members).list("page", 1);

        assertThat(page.nextCursor()).isEqualTo("next");
        var item = page.items().getFirst();
        assertThat(item.entryId()).isEqualTo(9);
        assertThat(item.createdAt()).isEqualTo(time);
        assertThat(item.student().fullName()).isEqualTo("Текущее имя");
        assertThat(item.teacher().fullName()).isEqualTo("Снимок автора");
        assertThat(item.activity().title()).isEqualTo("Снимок активности");
        assertThat(item.amount()).isEqualTo(38);
        assertThat(item.currency()).isEqualTo(Currency.BUA);
        verify(ledger).awards(Optional.of("page"), 1);
        verify(members).users(List.of(4L));
        verifyNoMoreInteractions(ledger, members);
    }

    @ParameterizedTest
    @EnumSource(Currency.class)
    void ratingSortsSelectedBalanceThenNameThenIdWithOneBatch(Currency currency) {
        groupExists();
        when(members.activeStudents(List.of(7L))).thenReturn(List.of(student(4, "Б"), student(3, "А"),
                student(2, "А"), student(1, "Я")));
        var top = switch (currency) {
            case AR -> new Balances(20, 0, 0);
            case BUA -> new Balances(0, 20, 0);
            case BOA -> new Balances(0, 0, 20);
        };
        when(ledger.balances(List.of(4L, 3L, 2L, 1L))).thenReturn(Map.of(1L, top,
                2L, new Balances(10, 10, 10), 3L, new Balances(10, 10, 10), 4L, new Balances(10, 10, 10)));

        var rating = service().get(7, currency);

        assertThat(rating.items()).extracting(row -> row.student().id()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(rating.sort()).isEqualTo(currency);
        verify(ledger).balances(List.of(4L, 3L, 2L, 1L));
        verifyNoMoreInteractions(ledger);
        var order = inOrder(members, scope);
        order.verify(members).groups(List.of(7L));
        order.verify(scope).requireAccessible(8, List.of(7L));
        order.verify(members).activeStudents(List.of(7L));
    }

    @Test
    void absentGroupFailsBeforeScopeAndLedger() {
        when(members.groups(List.of(7L))).thenReturn(Map.of());
        assertThatThrownBy(() -> service().get(7, Currency.AR)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ProblemCode.NOT_FOUND));
        verifyNoInteractions(scope, ledger, current);
    }

    @Test
    void emptyGroupRetainsMetadata() {
        groupExists();
        when(members.activeStudents(List.of(7L))).thenReturn(List.of());
        when(ledger.balances(List.of())).thenReturn(Map.of());
        var rating = service().get(7, Currency.AR);
        assertThat(rating.items()).isEmpty();
        assertThat(rating.group().name()).isEqualTo("Группа");
        assertThat(rating.group().faculty().code()).isEqualTo("Ф");
    }

    void groupExists() {
        when(members.groups(List.of(7L))).thenReturn(Map.of(7L, new GroupRef(7, "Группа", 5, "Ф", "Факультет")));
        when(current.require()).thenReturn(new AuthenticatedUser(8, Role.TEACHER, "Учитель", false, true, null));
    }

    StudentMember student(long id, String name) {
        return new StudentMember(id, name, 7, GroupMembershipState.ACTIVE, Instant.EPOCH);
    }

    GroupRatingService service() {
        return new GroupRatingService(members, scope, current, ledger);
    }
}

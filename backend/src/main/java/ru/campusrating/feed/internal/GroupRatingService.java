package ru.campusrating.feed.internal;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import ru.campusrating.feed.api.AwardsFeedController.UserRef;
import ru.campusrating.feed.api.GroupRatingsController.Balances;
import ru.campusrating.feed.api.GroupRatingsController.FacultyRef;
import ru.campusrating.feed.api.GroupRatingsController.GroupRating;
import ru.campusrating.feed.api.GroupRatingsController.RatingRow;
import ru.campusrating.feed.api.GroupRatingsController.StudyGroupRef;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.TeacherScope;
import ru.campusrating.ledger.Currency;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.ProblemCode;

@Service
public class GroupRatingService {
    private final GroupMembers members;
    private final TeacherScope scope;
    private final CurrentUser current;
    private final LedgerApi ledger;

    public GroupRatingService(GroupMembers members, TeacherScope scope, CurrentUser current, LedgerApi ledger) {
        this.members = members;
        this.scope = scope;
        this.current = current;
        this.ledger = ledger;
    }

    public GroupRating get(long groupId, Currency sort) {
        var ids = List.of(groupId);
        var group = members.groups(ids).get(groupId);
        if (group == null) {
            throw new ApiException(ProblemCode.NOT_FOUND, "Группа не найдена");
        }
        scope.requireAccessible(current.require().id(), ids);
        var students = members.activeStudents(ids);
        var balances = ledger.balances(students.stream().map(student -> student.studentId()).toList());
        var rows = students.stream().map(student -> {
            var balance = balances.get(student.studentId());
            return new RatingRow(new UserRef(student.studentId(), student.fullName()),
                    new Balances(balance.ar(), balance.bua(), balance.boa()));
        }).sorted(order(sort)).toList();
        var faculty = new FacultyRef(group.facultyId(), group.facultyCode(), group.facultyName());
        return new GroupRating(new StudyGroupRef(group.id(), group.name(), faculty), sort, rows);
    }

    private static Comparator<RatingRow> order(Currency sort) {
        return Comparator.<RatingRow>comparingInt(row -> switch (sort) {
            case AR -> row.balances().ar();
            case BUA -> row.balances().bua();
            case BOA -> row.balances().boa();
        }).reversed().thenComparing(row -> row.student().fullName()).thenComparingLong(row -> row.student().id());
    }
}

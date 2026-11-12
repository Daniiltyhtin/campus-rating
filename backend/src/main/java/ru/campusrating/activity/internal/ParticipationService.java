package ru.campusrating.activity.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.activity.PointsAwarded;
import ru.campusrating.activity.api.ActivityController.UserRef;
import ru.campusrating.activity.api.ParticipationController.AwardLine;
import ru.campusrating.activity.api.ParticipationController.ConfirmedParticipation;
import ru.campusrating.activity.api.ParticipationController.GroupBudgetCheck;
import ru.campusrating.activity.api.ParticipationController.Participant;
import ru.campusrating.activity.api.ParticipationController.ParticipantList;
import ru.campusrating.activity.api.ParticipationController.ParticipationPreview;
import ru.campusrating.activity.api.ParticipationController.ParticipationResult;
import ru.campusrating.activity.domain.ActivityType;
import ru.campusrating.activity.domain.ActivityWindow;
import ru.campusrating.activity.domain.AwardCalculator;
import ru.campusrating.activity.domain.BudgetPolicy;
import ru.campusrating.activity.domain.BudgetPolicy.GroupBudget;
import ru.campusrating.activity.domain.ParticipationLevel;
import ru.campusrating.activity.internal.ActivityRepository.ActivityRow;
import ru.campusrating.activity.internal.AwardRepository.AwardRow;
import ru.campusrating.identity.AuthenticatedUser;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.GroupMembershipState;
import ru.campusrating.identity.GroupRef;
import ru.campusrating.identity.StudentMember;
import ru.campusrating.ledger.Author;
import ru.campusrating.ledger.EntryKind;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.ledger.Posting;
import ru.campusrating.ledger.PostingResult;
import ru.campusrating.ledger.Source;
import ru.campusrating.ledger.SourceType;
import ru.campusrating.settings.SettingsApi;
import ru.campusrating.shared.domain.MoscowTime;
import ru.campusrating.shared.error.ApiException;
import ru.campusrating.shared.error.BudgetCheck;
import ru.campusrating.shared.error.BudgetExceededException;
import ru.campusrating.shared.error.FieldError;
import ru.campusrating.shared.error.ProblemCode;
import ru.campusrating.shared.error.StudentsRejectedException;
import ru.campusrating.shared.error.ValidationFailedException;

@Service
public class ParticipationService {
    public record Item(long studentId, ParticipationLevel level) {}

    private record Plan(ActivityRow activity, List<NewAward> awards, List<AwardLine> alreadyConfirmed) {
        Map<Long, Integer> needed() {
            var needed = new TreeMap<Long, Integer>();
            for (var award : awards) {
                needed.merge(award.groupId(), award.amount(), Integer::sum);
            }
            return needed;
        }

        int total() {
            return awards.stream().mapToInt(NewAward::amount).sum();
        }
    }

    private record Person(long studentId, String fullName, long groupId) {}

    private final ActivityRepository activities;
    private final AwardRepository awards;
    private final BudgetRepository budgets;
    private final CurrentUser current;
    private final GroupMembers members;
    private final SettingsApi settings;
    private final LedgerApi ledger;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;

    ParticipationService(ActivityRepository activities, AwardRepository awards, BudgetRepository budgets, CurrentUser current,
                         GroupMembers members, SettingsApi settings, LedgerApi ledger, Clock clock,
                         ApplicationEventPublisher publisher) {
        this.activities = activities;
        this.awards = awards;
        this.budgets = budgets;
        this.current = current;
        this.members = members;
        this.settings = settings;
        this.ledger = ledger;
        this.clock = clock;
        this.publisher = publisher;
    }

    @Transactional(readOnly = true)
    public ParticipantList participants(long activityId) {
        var activity = requireOwn(activityId, current.require());
        var awarded = awardsByStudent(activityId);
        var enrolled = activities.enrolledStudents(activityId);
        var people = new LinkedHashMap<Long, Person>();
        for (var member : candidates(activity, enrolled)) {
            people.put(member.studentId(), new Person(member.studentId(), member.fullName(), member.groupId()));
        }
        var groups = members.groups(people.values().stream().map(Person::groupId).distinct().toList());
        var items = people.values().stream()
                .sorted(byGroupAndName(groups))
                .map(person -> participant(person, groups.get(person.groupId()), activity.type(), enrolled, awarded.get(person.studentId())))
                .toList();
        return new ParticipantList(activityId, activity.type(), activity.maxPoints(), items);
    }

    @Transactional(readOnly = true)
    public ParticipationPreview preview(long activityId, List<Item> items) {
        var user = current.require();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var plan = plan(requireOwn(activityId, user), items, now);
        var needed = plan.needed();
        var check = check(needed, budgets.spent(user.id(), needed.keySet(), month(now)));
        var currency = plan.activity().type().currency();
        return new ParticipationPreview(currency, lines(plan.awards()), plan.alreadyConfirmed(), plan.total(),
                budgetViews(check), check.withinBudget());
    }

    @Transactional
    public ParticipationResult confirm(long activityId, List<Item> items) {
        var user = current.require();
        activities.lock(activityId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var plan = plan(requireOwn(activityId, user), items, now);
        var needed = plan.needed();
        var month = month(now);
        budgets.ensure(user.id(), needed.keySet(), month);
        var check = check(needed, budgets.lock(user.id(), needed.keySet(), month));
        if (!check.withinBudget()) {
            throw exceeded(check);
        }
        needed.forEach((group, amount) -> budgets.spend(user.id(), group, month, amount));
        post(plan, user, now);
        var currency = plan.activity().type().currency();
        return new ParticipationResult(currency, lines(plan.awards()), plan.alreadyConfirmed(), plan.total());
    }

    private ActivityRow requireOwn(long activityId, AuthenticatedUser user) {
        var activity = activities.find(activityId, user.id())
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "Активность не найдена"));
        if (activity.authorId() != user.id()) {
            throw new ApiException(ProblemCode.FORBIDDEN, "Активность принадлежит другому преподавателю");
        }
        return activity;
    }

    private Collection<StudentMember> candidates(ActivityRow activity, Set<Long> enrolled) {
        if (activity.type() == ActivityType.SOCIAL) {
            return members.memberships(enrolled).values();
        }
        return members.activeStudents(activityGroups(activity.id()));
    }

    private Comparator<Person> byGroupAndName(Map<Long, GroupRef> groups) {
        return Comparator.comparing((Person person) -> groups.get(person.groupId()), ActivityViews.BY_NAME)
                .thenComparing(Person::fullName)
                .thenComparingLong(Person::studentId);
    }

    private Participant participant(Person person, GroupRef group, ActivityType type, Set<Long> enrolled, @Nullable AwardRow award) {
        Boolean isEnrolled = type == ActivityType.SOCIAL ? enrolled.contains(person.studentId()) : null;
        var confirmed = award == null ? null : new ConfirmedParticipation(award.level(), award.amount(), award.confirmedAt());
        return new Participant(new UserRef(person.studentId(), person.fullName()), ActivityViews.group(group), isEnrolled, confirmed);
    }

    private Plan plan(ActivityRow activity, List<Item> items, Instant now) {
        requireUnique(items);
        if (!ActivityWindow.at(activity.type(), activity.startsAt(), now).confirmationOpen()) {
            throw new ApiException(ProblemCode.INVALID_STATE, "Подтверждать участие можно после начала активности");
        }
        var existing = awardsByStudent(activity.id());
        reject(ProblemCode.ALREADY_CONFIRMED, "Участие уже подтверждено с другим уровнем", items,
                item -> existing.containsKey(item.studentId()) && existing.get(item.studentId()).level() != item.level().percent());
        var fresh = items.stream().filter(item -> !existing.containsKey(item.studentId())).toList();
        var memberships = members.memberships(fresh.stream().map(Item::studentId).toList());
        var groups = new HashSet<>(activityGroups(activity.id()));
        reject(ProblemCode.STUDENT_NOT_ELIGIBLE, "Студент не может участвовать в активности", fresh,
                item -> !eligible(memberships.get(item.studentId()), groups));
        if (activity.type() == ActivityType.SOCIAL) {
            var enrolled = activities.enrolledStudents(activity.id());
            reject(ProblemCode.NOT_ENROLLED, "Студент не записан на активность", fresh, item -> !enrolled.contains(item.studentId()));
        }
        var awardsToPost = fresh.stream()
                .sorted(Comparator.comparingLong(Item::studentId))
                .map(item -> newAward(item, memberships.get(item.studentId()).groupId(), activity.maxPoints()))
                .toList();
        var already = items.stream()
                .filter(item -> existing.containsKey(item.studentId()))
                .map(item -> existing.get(item.studentId()))
                .sorted(Comparator.comparingLong(AwardRow::studentId))
                .map(award -> new AwardLine(award.studentId(), award.groupId(), award.level(), award.amount()))
                .toList();
        return new Plan(activity, awardsToPost, already);
    }

    private void requireUnique(List<Item> items) {
        if (items.stream().map(Item::studentId).distinct().count() != items.size()) {
            var error = new FieldError("items", "INVALID", "Студенты не должны повторяться");
            throw new ValidationFailedException("Проверьте поля запроса", List.of(error));
        }
    }

    private void reject(ProblemCode code, String detail, List<Item> items, Predicate<Item> rejected) {
        var ids = items.stream().filter(rejected).map(Item::studentId).toList();
        if (!ids.isEmpty()) {
            throw new StudentsRejectedException(code, detail, ids);
        }
    }

    private boolean eligible(@Nullable StudentMember member, Set<Long> groups) {
        return member != null && member.state() == GroupMembershipState.ACTIVE && groups.contains(member.groupId());
    }

    private NewAward newAward(Item item, long groupId, int maxPoints) {
        return new NewAward(item.studentId(), groupId, item.level().percent(), AwardCalculator.amount(maxPoints, item.level()));
    }

    private Map<Long, AwardRow> awardsByStudent(long activityId) {
        return awards.forActivity(activityId).stream().collect(Collectors.toMap(AwardRow::studentId, Function.identity()));
    }

    private List<Long> activityGroups(long activityId) {
        return activities.groups(List.of(activityId)).get(activityId);
    }

    private LocalDate month(Instant now) {
        return MoscowTime.month(now).atDay(1);
    }

    private BudgetPolicy.BudgetCheck check(Map<Long, Integer> needed, Map<Long, Integer> spent) {
        var groups = new ArrayList<GroupBudget>();
        needed.forEach((group, amount) -> groups.add(new GroupBudget(group, amount, spent.getOrDefault(group, 0))));
        return BudgetPolicy.check(groups, settings.monthlyBudgetLimit());
    }

    private List<GroupBudgetCheck> budgetViews(BudgetPolicy.BudgetCheck check) {
        var names = members.groups(check.groups().stream().map(BudgetPolicy.GroupCheck::groupId).toList());
        return check.groups().stream()
                .map(group -> new GroupBudgetCheck(group.groupId(), names.get(group.groupId()).name(), group.needed(),
                        group.remaining(), group.shortfall()))
                .toList();
    }

    private BudgetExceededException exceeded(BudgetPolicy.BudgetCheck check) {
        var shortfalls = budgetViews(check).stream()
                .filter(group -> group.shortfall() > 0)
                .map(group -> new BudgetCheck(group.groupId(), group.groupName(), group.needed(), group.remaining(), group.shortfall()))
                .toList();
        var names = shortfalls.stream().map(BudgetCheck::groupName).collect(Collectors.joining(", "));
        return new BudgetExceededException("Не хватает бюджета группы " + names, shortfalls);
    }

    private List<AwardLine> lines(List<NewAward> newAwards) {
        return newAwards.stream()
                .map(award -> new AwardLine(award.studentId(), award.groupId(), award.level(), award.amount()))
                .toList();
    }

    private void post(Plan plan, AuthenticatedUser user, Instant now) {
        if (plan.awards().isEmpty()) {
            return;
        }
        var activity = plan.activity();
        var currency = activity.type().currency();
        var source = new Source(SourceType.ACTIVITY, activity.id(), activity.title());
        var author = new Author(user.id(), user.fullName());
        var ids = new ArrayList<Long>();
        var postings = new ArrayList<Posting>();
        for (var award : plan.awards()) {
            ids.add(awards.insert(activity.id(), user.id(), award, currency, now));
            String key = "award:" + activity.id() + ":" + award.studentId();
            postings.add(new Posting(award.studentId(), currency, EntryKind.ACTIVITY_AWARD, award.amount(), source, author, key));
        }
        var results = ledger.post(postings);
        for (int index = 0; index < ids.size(); index++) {
            long entry = entryId(results.get(index));
            awards.link(ids.get(index), entry);
            var award = plan.awards().get(index);
            publisher.publishEvent(new PointsAwarded(entry, award.studentId(), award.amount(), currency, activity.id(),
                    activity.title(), now));
        }
    }

    private long entryId(PostingResult result) {
        if (result instanceof PostingResult.Posted posted) {
            return posted.entryId();
        }
        throw new IllegalStateException("Начисление по активности не проведено: " + result);
    }
}

package ru.campusrating.poll.internal;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.GroupMembershipState;
import ru.campusrating.identity.GroupRef;
import ru.campusrating.identity.StudentMember;
import ru.campusrating.poll.api.PollController.FacultyRef;
import ru.campusrating.poll.api.PollController.Poll;
import ru.campusrating.poll.api.PollController.PollOption;
import ru.campusrating.poll.api.PollController.PollOptionResult;
import ru.campusrating.poll.api.PollController.PollQuestion;
import ru.campusrating.poll.api.PollController.PollQuestionResult;
import ru.campusrating.poll.api.PollController.PollResults;
import ru.campusrating.poll.api.PollController.StudyGroupRef;
import ru.campusrating.poll.api.PollController.UserRef;
import ru.campusrating.poll.domain.PollRules;
import ru.campusrating.poll.internal.PollRepository.PollRow;

@Component
class PollViews {
    private final PollRepository repository;
    private final GroupMembers members;
    private final Clock clock;

    PollViews(PollRepository repository, GroupMembers members, Clock clock) {
        this.repository = repository;
        this.members = members;
        this.clock = clock;
    }

    Poll poll(PollRow row, @Nullable Boolean voted, boolean author) {
        var groups = groupIds(row.id());
        var options = repository.options(row.id());
        var questions = repository.questions(row.id()).stream().map(question -> new PollQuestion(question.id(), question.text(),
                question.type(), options.stream().filter(option -> option.questionId() == question.id())
                        .map(option -> new PollOption(option.id(), option.text())).toList())).toList();
        var authorRef = members.users(List.of(row.authorId())).get(row.authorId());
        boolean showResults = author || Boolean.TRUE.equals(voted);
        return new Poll(row.id(), row.title(), row.description(), row.endsAt(), PollRules.open(row.endsAt(), clock.instant()),
                row.anonymous(), groupRefs(groups), new UserRef(row.authorId(), Objects.requireNonNull(authorRef).fullName()),
                questions, voted, author ? eligible(groups).size() : null, showResults ? results(row.id()) : null);
    }

    PollResults results(long poll) {
        int voted = repository.participants(poll);
        var byQuestion = new LinkedHashMap<Long, List<PollOptionResult>>();
        repository.questions(poll).forEach(question -> byQuestion.put(question.id(), new java.util.ArrayList<>()));
        for (var option : repository.options(poll)) {
            byQuestion.get(option.questionId()).add(new PollOptionResult(option.id(), option.votes(), share(option.votes(), voted)));
        }
        return new PollResults(voted, byQuestion.entrySet().stream()
                .map(entry -> new PollQuestionResult(entry.getKey(), List.copyOf(entry.getValue()))).toList());
    }

    List<Long> groupIds(long poll) {
        return repository.groups(List.of(poll)).stream().map(PollRepository.GroupLink::groupId).toList();
    }

    List<StudentMember> eligible(Collection<Long> groups) {
        return members.activeStudents(groups).stream()
                .filter(member -> member.state() == GroupMembershipState.ACTIVE && groups.contains(member.groupId())).toList();
    }

    List<StudyGroupRef> groupRefs(Collection<Long> groups) {
        Map<Long, GroupRef> refs = members.groups(groups);
        return groups.stream().map(refs::get).filter(Objects::nonNull).map(PollViews::groupRef).toList();
    }

    static StudyGroupRef groupRef(GroupRef group) {
        return new StudyGroupRef(group.id(), group.name(), new FacultyRef(group.facultyId(), group.facultyCode(), group.facultyName()));
    }

    private static int share(int votes, int voted) {
        return voted == 0 ? 0 : (int) Math.round(100.0 * votes / voted);
    }
}

package ru.campusrating.poll.api;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.poll.domain.PollQuestionType;
import ru.campusrating.poll.internal.PollService;
import ru.campusrating.poll.internal.VoteService;

@RestController
@RequestMapping("/api/v1/polls")
public class PollController {
    private final PollService polls;
    private final VoteService votes;

    PollController(PollService polls, VoteService votes) {
        this.polls = polls;
        this.votes = votes;
    }

    @Schema(requiredProperties = {"text", "type", "options"})
    public record PollQuestionCreate(@NotNull @Size(min = 1, max = 500) String text, @NotNull PollQuestionType type,
            @NotNull @Size(min = 2, max = 10) List<@NotNull @Size(min = 1, max = 200) String> options) {}

    @Schema(requiredProperties = {"title", "description", "endsAt", "groupIds", "anonymous", "questions"})
    public record PollCreate(@NotNull @Size(min = 1, max = 200) String title, @NotNull @Size(max = 2000) String description,
            @NotNull Instant endsAt,
            @NotEmpty @ArraySchema(minItems = 1, uniqueItems = true) List<@NotNull @Min(1) Long> groupIds,
            @NotNull Boolean anonymous,
            @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid PollQuestionCreate> questions) {}

    @Schema(requiredProperties = {"id", "text"})
    public record PollOption(@Min(1) long id, String text) {}

    @Schema(requiredProperties = {"id", "text", "type", "options"})
    public record PollQuestion(@Min(1) long id, String text, PollQuestionType type, List<PollOption> options) {}

    @Schema(requiredProperties = {"optionId", "votes", "share"})
    public record PollOptionResult(@Min(1) long optionId, @Min(0) int votes, @Min(0) @Max(100) int share) {}

    @Schema(requiredProperties = {"questionId", "options"})
    public record PollQuestionResult(@Min(1) long questionId, List<PollOptionResult> options) {}

    @Schema(requiredProperties = {"voted", "questions"})
    public record PollResults(@Min(0) int voted, List<PollQuestionResult> questions) {}

    @Schema(requiredProperties = {"id", "fullName"})
    public record UserRef(@Min(1) long id, String fullName) {}

    @Schema(requiredProperties = {"id", "name", "faculty"})
    public record StudyGroupRef(@Min(1) long id, String name, FacultyRef faculty) {}

    @Schema(requiredProperties = {"id", "code", "name"})
    public record FacultyRef(@Min(1) long id, String code, String name) {}

    @Schema(requiredProperties = {"id", "title", "description", "endsAt", "open", "anonymous", "groups", "author", "questions",
            "voted", "eligible", "results"})
    public record Poll(@Min(1) long id, String title, String description, Instant endsAt, boolean open, boolean anonymous,
            List<StudyGroupRef> groups, UserRef author, List<PollQuestion> questions,
            @Schema(types = {"boolean", "null"}) @Nullable Boolean voted,
            @Schema(types = {"integer", "null"}, format = "int32", minimum = "0") @Nullable Integer eligible,
            @Nullable PollResults results) {}

    @Schema(requiredProperties = {"id", "title", "endsAt", "open", "anonymous", "voted", "groups"})
    public record PollSummary(@Min(1) long id, String title, Instant endsAt, boolean open, boolean anonymous,
            @Schema(types = {"boolean", "null"}) @Nullable Boolean voted, List<StudyGroupRef> groups) {}

    @Schema(requiredProperties = "items")
    public record PollList(List<PollSummary> items) {}

    @Schema(requiredProperties = {"questionId", "optionIds"})
    public record PollAnswer(@NotNull @Min(1) Long questionId,
            @NotNull @ArraySchema(minItems = 1, uniqueItems = true) List<@NotNull @Min(1) Long> optionIds) {
        @Override
        public String toString() {
            return "PollAnswer[questionId=" + questionId + ", optionCount=" + (optionIds == null ? 0 : optionIds.size()) + "]";
        }
    }

    @Schema(requiredProperties = "answers")
    public record PollVote(@NotEmpty List<@NotNull @Valid PollAnswer> answers) {
        @Override
        public String toString() {
            return "PollVote[answerCount=" + (answers == null ? 0 : answers.size()) + "]";
        }
    }

    @Schema(requiredProperties = {"student", "group", "answers"})
    public record PollResponse(UserRef student, StudyGroupRef group, List<PollAnswer> answers) {}

    @Schema(requiredProperties = "items")
    public record PollResponseList(List<PollResponse> items) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Poll createPoll(@Valid @RequestBody PollCreate request) {
        return polls.create(request);
    }

    @GetMapping
    public PollList listPolls() {
        return new PollList(polls.list());
    }

    @GetMapping("/{pollId}")
    public Poll getPoll(@PathVariable @Min(1) long pollId) {
        return polls.get(pollId);
    }

    @PostMapping("/{pollId}/vote")
    public PollResults votePoll(@PathVariable @Min(1) long pollId, @Valid @RequestBody PollVote request) {
        return votes.vote(pollId, request.answers());
    }

    @GetMapping("/{pollId}/responses")
    public PollResponseList listPollResponses(@PathVariable @Min(1) long pollId) {
        return new PollResponseList(polls.responses(pollId));
    }
}

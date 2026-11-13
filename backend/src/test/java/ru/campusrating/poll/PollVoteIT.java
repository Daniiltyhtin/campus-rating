package ru.campusrating.poll;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class PollVoteIT extends PollTestSupport {
    @Autowired PlatformTransactionManager transactions;

    @Test
    void voteWaitsForClosingLockAndRejectsCommittedClosure() throws Exception {
        var created = formatPoll(true);
        long pollId = created.path("id").asLong();
        var session = studentSession();
        var tx = new TransactionTemplate(transactions);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Response> call = tx.execute(status -> {
                jdbc.queryForObject("select id from poll where id=? for update", Long.class, pollId);
                int holder = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                var pending = executor.submit(() -> vote(pollId, session,
                        answer(questionId(created, 0), optionId(created, 0, 1))));
                try {
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(count(
                            "select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))", holder))
                            .isPositive());
                    assertThat(pending.isDone()).isFalse();
                    update("update poll set closed_at=? where id=?", NOW, pollId);
                    return pending;
                } catch (RuntimeException | Error failure) {
                    pending.cancel(true);
                    throw failure;
                }
            });
            var response = call.get(20, TimeUnit.SECONDS);
            assertThat(response.status()).isEqualTo(422);
            problem(JSON.readTree(response.body()), "POLL_CLOSED");
        }
        assertThat(count("select count(*) from poll_participation where poll_id=?", pollId)).isZero();
        assertThat(count("select sum(vote_count) from poll_option where question_id=?", questionId(created, 0))).isZero();
    }

    @ParameterizedTest
    @CsvSource({"1,1", "1,2", "1,3", "2,1", "2,2", "2,3", "3,1", "3,2", "3,3"})
    void sharesStayWithinOneHundredForEveryTwoOptionBallotCombination(int first, int second) {
        var created = createPoll(pollBody("Форматы", ENDS, true, List.of(group),
                List.of(question("Какие форматы подходят?", "MULTIPLE", "Тест", "Устно"))));
        long pollId = created.path("id").asLong();
        long question = questionId(created, 0);
        long[] options = {optionId(created, 0, 0), optionId(created, 0, 1)};
        JsonNode results = null;
        var sessions = List.of(studentSession(), classmateSession());
        int[] masks = {first, second};
        for (int voter = 0; voter < masks.length; voter++) {
            var chosen = new ArrayList<Long>();
            for (int option = 0; option < options.length; option++) {
                if ((masks[voter] & (1 << option)) != 0) chosen.add(options[option]);
            }
            results = vote(pollId, sessions.get(voter), 200,
                    answer(question, chosen.stream().mapToLong(Long::longValue).toArray()));
            assertThat(results.path("voted").asInt()).isEqualTo(voter + 1);
            for (long option : options) {
                var result = optionResult(results, option);
                assertThat(result.path("votes").asInt()).isBetween(0, voter + 1);
                assertThat(result.path("share").asInt()).isBetween(0, 100);
            }
        }
        for (int option = 0; option < options.length; option++) {
            int votes = ((first >> option) & 1) + ((second >> option) & 1);
            assertVotes(results, options[option], votes, votes * 50);
        }
    }

    @Test
    void ae2VoteShowsCountsAndSharesAndRepeatIsAlreadyVoted() {
        var created = formatPoll(true);
        long pollId = created.path("id").asLong();
        long when = questionId(created, 0), morning = optionId(created, 0, 0), evening = optionId(created, 0, 1);

        var before = poll(pollId, classmateSession(), 200);
        assertThat(before.path("results").isNull()).isTrue();
        assertThat(before.path("voted").asBoolean()).isFalse();

        var results = vote(pollId, studentSession(), 200, answer(when, evening));
        assertThat(results.path("voted").asInt()).isEqualTo(1);
        assertThat(results.path("questions").get(0).path("questionId").asLong()).isEqualTo(when);
        assertVotes(results, morning, 0, 0);
        assertVotes(results, evening, 1, 100);

        var mine = poll(pollId, studentSession(), 200);
        assertThat(mine.path("voted").asBoolean()).isTrue();
        assertThat(mine.path("results")).isEqualTo(results);

        problem(vote(pollId, studentSession(), 409, answer(when, morning)), "ALREADY_VOTED");
        var author = poll(pollId, teacherSession(), 200).path("results");
        assertThat(author.path("voted").asInt()).isEqualTo(1);
        assertVotes(author, morning, 0, 0);
        assertVotes(author, evening, 1, 100);
        assertThat(count("select count(*) from poll_participation")).isEqualTo(1);

        var classmateView = poll(pollId, classmateSession(), 200);
        assertThat(classmateView.path("results").isNull()).isTrue();
        assertThat(classmateView.path("voted").asBoolean()).isFalse();
    }

    @Test
    void multipleChoiceCountsEveryChosenOptionAndSharesAreOfVoters() {
        var created = createPoll(pollBody("Форматы", ENDS, false, List.of(group),
                List.of(question("Какие форматы подходят?", "MULTIPLE", "Тест", "Устно", "Письменно"))));
        long pollId = created.path("id").asLong();
        long q = questionId(created, 0), test = optionId(created, 0, 0), oral = optionId(created, 0, 1),
                written = optionId(created, 0, 2);
        vote(pollId, studentSession(), 200, answer(q, test, oral));
        var results = vote(pollId, classmateSession(), 200, answer(q, oral));
        assertThat(results.path("voted").asInt()).isEqualTo(2);
        assertVotes(results, test, 1, 50);
        assertVotes(results, oral, 2, 100);
        assertVotes(results, written, 0, 0);
    }

    @Test
    void invalidBallotsAreRejectedByFieldAndLeaveNoTrace() {
        var created = createPoll(pollBody("Два вопроса", ENDS, true, List.of(group), List.of(
                question("Когда удобнее?", "SINGLE", "Утро", "Вечер"),
                question("Какие форматы подходят?", "MULTIPLE", "Тест", "Устно"))));
        long pollId = created.path("id").asLong();
        long when = questionId(created, 0), format = questionId(created, 1);
        long morning = optionId(created, 0, 0), evening = optionId(created, 0, 1);
        long test = optionId(created, 1, 0), oral = optionId(created, 1, 1);

        assertThat(field(vote(pollId, studentSession(), 400, answer(when, morning, evening), answer(format, test))))
                .isEqualTo("answers[0].optionIds");
        assertThat(field(vote(pollId, studentSession(), 400, answer(when, test), answer(format, oral))))
                .isEqualTo("answers[0].optionIds");
        assertThat(field(vote(pollId, studentSession(), 400, answer(when, morning), answer(format, test, test))))
                .startsWith("answers[1].optionIds");
        assertThat(field(vote(pollId, studentSession(), 400, answer(when, morning), answer(format))))
                .startsWith("answers[1].optionIds");
        assertThat(field(vote(pollId, studentSession(), 400, answer(when, morning)))).startsWith("answers");
        assertThat(field(vote(pollId, studentSession(), 400, answer(when, morning), answer(when, evening), answer(format, test))))
                .startsWith("answers[1]");
        assertThat(field(vote(pollId, studentSession(), 400, answer(Long.MAX_VALUE, morning), answer(format, test))))
                .startsWith("answers[0]");
        assertThat(count("select count(*) from poll_participation")).isZero();

        var results = vote(pollId, studentSession(), 200, answer(format, test, oral), answer(when, evening));
        assertThat(results.path("voted").asInt()).isEqualTo(1);
        assertVotes(results, morning, 0, 0);
        assertVotes(results, evening, 1, 100);
        assertVotes(results, test, 1, 100);
        assertVotes(results, oral, 1, 100);
    }

    @Test
    void parallelVotesOfOneStudentSucceedExactlyOnce() throws Exception {
        var created = formatPoll(true);
        long pollId = created.path("id").asLong();
        long when = questionId(created, 0), morning = optionId(created, 0, 0), evening = optionId(created, 0, 1);
        var first = login("student");
        var second = login("student");
        var barrier = new CyclicBarrier(2);
        var statuses = new ArrayList<Integer>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Response>> calls = List.of(
                    executor.submit(() -> { barrier.await(); return vote(pollId, first, answer(when, morning)); }),
                    executor.submit(() -> { barrier.await(); return vote(pollId, second, answer(when, evening)); }));
            for (var call : calls) {
                var response = call.get(20, TimeUnit.SECONDS);
                statuses.add(response.status());
                if (response.status() == 409) {
                    problem(JSON.readTree(response.body()), "ALREADY_VOTED");
                }
            }
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var results = poll(pollId, teacherSession(), 200).path("results");
        assertThat(results.path("voted").asInt()).isEqualTo(1);
        assertThat(optionResult(results, morning).path("votes").asInt() + optionResult(results, evening).path("votes").asInt())
                .isEqualTo(1);
        assertThat(count("select count(*) from poll_participation")).isEqualTo(1);
    }

    @Test
    void ae5VoteAfterEndIsPollClosed() {
        var created = formatPoll(true);
        long pollId = created.path("id").asLong();
        long when = questionId(created, 0), evening = optionId(created, 0, 1);
        clock.set(ENDS);
        problem(vote(pollId, studentSession(), 422, answer(when, evening)), "POLL_CLOSED");
        clock.set(ENDS.plusSeconds(60));
        problem(vote(pollId, classmateSession(), 422, answer(when, evening)), "POLL_CLOSED");
        assertThat(count("select count(*) from poll_participation")).isZero();
    }

    @Test
    void onlyActiveStudentsOfTargetGroupsVote() {
        var created = formatPoll(true);
        long pollId = created.path("id").asLong();
        long when = questionId(created, 0), evening = optionId(created, 0, 1);
        problem(vote(pollId, session("pending"), 422, answer(when, evening)), "MEMBERSHIP_NOT_ACTIVE");
        problem(vote(pollId, session("neighbour"), 403, answer(when, evening)), "FORBIDDEN");
        problem(vote(pollId, session("outsider"), 403, answer(when, evening)), "FORBIDDEN");
        problem(vote(pollId, teacherSession(), 403, answer(when, evening)), "FORBIDDEN");
        problem(vote(pollId, session("otherTeacher"), 403, answer(when, evening)), "FORBIDDEN");
        vote(pollId, session("admin"), 403, answer(when, evening));
        vote(pollId, null, 401, answer(when, evening));
        problem(vote(Long.MAX_VALUE, studentSession(), 404, answer(when, evening)), "NOT_FOUND");
        assertThat(count("select count(*) from poll_participation")).isZero();
        assertThat(poll(pollId, teacherSession(), 200).path("results").path("voted").asInt()).isZero();
    }

    @Test
    void afterEndVoterSeesResultsAndNonVoterDoesNot() {
        var created = formatPoll(true);
        long pollId = created.path("id").asLong();
        long when = questionId(created, 0), evening = optionId(created, 0, 1);
        vote(pollId, studentSession(), 200, answer(when, evening));
        clock.set(ENDS.plusSeconds(3600));
        var voter = poll(pollId, studentSession(), 200);
        assertThat(voter.path("open").asBoolean()).isFalse();
        assertThat(voter.path("voted").asBoolean()).isTrue();
        assertVotes(voter.path("results"), evening, 1, 100);
        var nonVoter = poll(pollId, classmateSession(), 200);
        assertThat(nonVoter.path("open").asBoolean()).isFalse();
        assertThat(nonVoter.path("voted").asBoolean()).isFalse();
        assertThat(nonVoter.path("results").isNull()).isTrue();
        assertThat(poll(pollId, session("pending"), 200).path("results").isNull()).isTrue();
        var listed = request(get(POLLS), studentSession(), 200).path("items").get(0);
        assertThat(listed.path("voted").asBoolean()).isTrue();
        assertThat(request(get(POLLS), classmateSession(), 200).path("items").get(0).path("voted").asBoolean()).isFalse();
    }

    @Test
    void ae3AuthorOfNamedPollSeesEligibleAndAnswersByStudent() {
        var created = formatPoll(false);
        long pollId = created.path("id").asLong();
        long when = questionId(created, 0), morning = optionId(created, 0, 0), evening = optionId(created, 0, 1);
        vote(pollId, studentSession(), 200, answer(when, evening));
        vote(pollId, classmateSession(), 200, answer(when, morning));

        var author = poll(pollId, teacherSession(), 200);
        assertThat(author.path("voted").isNull()).isTrue();
        assertThat(author.path("eligible").asInt()).isEqualTo(6);
        assertThat(author.path("results").path("voted").asInt()).isEqualTo(2);
        assertVotes(author.path("results"), morning, 1, 50);
        assertVotes(author.path("results"), evening, 1, 50);

        var items = request(get(POLLS + "/" + pollId + "/responses"), teacherSession(), 200).path("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("student").path("fullName").asText()).isEqualTo(CLASSMATE_NAME);
        var smirnova = items.get(1);
        assertThat(smirnova.path("student").path("id").asLong()).isEqualTo(student);
        assertThat(smirnova.path("student").path("fullName").asText()).isEqualTo(STUDENT_NAME);
        assertThat(smirnova.path("group").path("id").asLong()).isEqualTo(group);
        assertThat(smirnova.path("group").path("name").asText()).isEqualTo("ИВТ-21");
        assertThat(smirnova.path("answers")).hasSize(1);
        assertThat(smirnova.path("answers").get(0).path("questionId").asLong()).isEqualTo(when);
        assertThat(longs(smirnova.path("answers").get(0).path("optionIds"))).containsExactly(evening);

        update("update group_membership set state='ACTIVE', activated_at=? where student_id=?", NOW, pending);
        assertThat(poll(pollId, teacherSession(), 200).path("eligible").asInt()).isEqualTo(7);
    }

    @Test
    void namedResponsesAreOrderedByFullNameThenId() {
        long twin = student("twin", STUDENT_NAME, group, "ACTIVE");
        var created = formatPoll(false);
        long pollId = created.path("id").asLong();
        long when = questionId(created, 0), morning = optionId(created, 0, 0);
        for (var name : List.of("twin", "student", "classmate", "sixth")) {
            vote(pollId, session(name), 200, answer(when, morning));
        }
        var items = request(get(POLLS + "/" + pollId + "/responses"), teacherSession(), 200).path("items");
        var names = new ArrayList<String>();
        items.forEach(item -> names.add(item.path("student").path("fullName").asText()));
        assertThat(names).containsExactly("Дмитриева Дарья Сергеевна", CLASSMATE_NAME, STUDENT_NAME, STUDENT_NAME);
        assertThat(items.get(2).path("student").path("id").asLong()).isEqualTo(Math.min(student, twin));
        assertThat(items.get(3).path("student").path("id").asLong()).isEqualTo(Math.max(student, twin));
    }

    @Test
    void ae3AnonymousPollHasNoAnswersByStudentAndOnlyAuthorReadsResponses() {
        var anonymous = formatPoll(true);
        long anonymousId = anonymous.path("id").asLong();
        vote(anonymousId, studentSession(), 200, answer(questionId(anonymous, 0), optionId(anonymous, 0, 1)));
        var author = poll(anonymousId, teacherSession(), 200);
        assertThat(author.path("eligible").asInt()).isEqualTo(6);
        assertThat(author.path("results").path("voted").asInt()).isEqualTo(1);
        problem(request(get(POLLS + "/" + anonymousId + "/responses"), teacherSession(), 409), "INVALID_STATE");

        long named = formatPoll(false).path("id").asLong();
        String responses = POLLS + "/" + named + "/responses";
        problem(request(get(responses), session("otherTeacher"), 403), "FORBIDDEN");
        request(get(responses), studentSession(), 403);
        request(get(responses), session("admin"), 403);
        request(get(responses), null, 401);
        problem(request(get(POLLS + "/" + Long.MAX_VALUE + "/responses"), teacherSession(), 404), "NOT_FOUND");
    }

    private static List<Long> longs(JsonNode values) {
        var result = new ArrayList<Long>();
        values.forEach(value -> result.add(value.asLong()));
        return result;
    }
}

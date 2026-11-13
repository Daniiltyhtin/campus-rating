package ru.campusrating.poll;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class PollIT extends PollTestSupport {

    @Test
    void createdPollIsReturnedWithQuestionsAndIsVisibleToAuthorAndTargetStudents() {
        var body = pollBody("Формат зачёта", ENDS, false, List.of(group), List.of(
                question("Когда удобнее?", "SINGLE", "Утро", "Вечер"),
                question("Какие форматы подходят?", "MULTIPLE", "Тест", "Устно", "Письменно")));
        var created = createPoll(body);
        long pollId = created.path("id").asLong();
        assertThat(pollId).isPositive();
        assertThat(created.path("title").asText()).isEqualTo("Формат зачёта");
        assertThat(created.path("description").asText()).isEqualTo("Выберите удобное время.");
        assertThat(Instant.parse(created.path("endsAt").asText())).isEqualTo(ENDS);
        assertThat(created.path("open").asBoolean()).isTrue();
        assertThat(created.path("anonymous").asBoolean()).isFalse();
        assertThat(ids(created.path("groups"))).containsExactly(group);
        assertThat(created.path("groups").get(0).path("name").asText()).isEqualTo("ИВТ-21");
        assertThat(created.path("groups").get(0).path("faculty").path("id").asLong()).isEqualTo(faculty);
        assertThat(created.path("author").path("id").asLong()).isEqualTo(teacher);
        assertThat(created.path("author").path("fullName").asText()).isEqualTo(TEACHER_NAME);
        var questions = created.path("questions");
        assertThat(questions).hasSize(2);
        assertThat(questions.get(0).path("text").asText()).isEqualTo("Когда удобнее?");
        assertThat(questions.get(0).path("type").asText()).isEqualTo("SINGLE");
        assertThat(texts(questions.get(0).path("options"))).containsExactly("Утро", "Вечер");
        assertThat(questions.get(1).path("type").asText()).isEqualTo("MULTIPLE");
        assertThat(texts(questions.get(1).path("options"))).containsExactly("Тест", "Устно", "Письменно");
        assertThat(created.path("voted").isNull()).isTrue();
        assertThat(created.path("eligible").asInt()).isEqualTo(6);
        var results = created.path("results");
        assertThat(results.path("voted").asInt()).isZero();
        for (int option = 0; option < 2; option++) {
            assertVotes(results, optionId(created, 0, option), 0, 0);
        }

        var forAuthor = poll(pollId, teacherSession(), 200);
        assertThat(forAuthor.path("questions")).isEqualTo(created.path("questions"));
        assertThat(forAuthor.path("eligible").asInt()).isEqualTo(6);

        var forStudent = poll(pollId, studentSession(), 200);
        assertThat(forStudent.path("questions")).isEqualTo(created.path("questions"));
        assertThat(forStudent.path("voted").asBoolean()).isFalse();
        assertThat(forStudent.path("voted").isBoolean()).isTrue();
        assertThat(forStudent.path("eligible").isNull()).isTrue();
        assertThat(forStudent.path("results").isNull()).isTrue();
        assertThat(forStudent.path("open").asBoolean()).isTrue();
    }

    @Test
    void creationRejectsEachInvalidFieldWith400AndCreatesNothing() {
        var invalid = new ArrayList<Case>();
        invalid.add(new Case("endsAt", with(formatPoll(true, group), "endsAt", NOW.minusSeconds(1).toString())));
        invalid.add(new Case("endsAt", with(formatPoll(true, group), "endsAt", NOW.toString())));
        invalid.add(new Case("title", with(formatPoll(true, group), "title", "")));
        invalid.add(new Case("title", with(formatPoll(true, group), "title", "Т".repeat(201))));
        invalid.add(new Case("description", with(formatPoll(true, group), "description", "О".repeat(2001))));
        invalid.add(new Case("groupIds", with(formatPoll(true, group), "groupIds", List.of())));
        invalid.add(new Case("groupIds", with(formatPoll(true, group), "groupIds", List.of(group, group))));
        invalid.add(new Case("questions", with(formatPoll(true, group), "questions", List.of())));
        invalid.add(new Case("questions", with(formatPoll(true, group), "questions", questions(21, 2))));
        invalid.add(new Case("questions[0].text", withQuestion(question("", "SINGLE", "Утро", "Вечер"))));
        invalid.add(new Case("questions[0].text", withQuestion(question("В".repeat(501), "SINGLE", "Утро", "Вечер"))));
        invalid.add(new Case("questions[0].options", withQuestion(question("Когда?", "SINGLE", "Утро"))));
        invalid.add(new Case("questions[0].options", withQuestion(question("Когда?", "SINGLE", options(11)))));
        invalid.add(new Case("questions[0].options", withQuestion(question("Когда?", "SINGLE", "Утро", " Утро "))));
        invalid.add(new Case("questions[0].options", withQuestion(question("Когда?", "MULTIPLE", "Утро", "Вечер", "Утро"))));
        invalid.add(new Case("questions[0].options", withQuestion(question("Когда?", "SINGLE", "Утро", ""))));
        invalid.add(new Case("questions[0].options", withQuestion(question("Когда?", "SINGLE", "Утро", "В".repeat(201)))));
        for (var item : invalid) {
            var result = create(item.body(), teacherSession(), 400);
            assertThat(field(result)).as("тело %s", item.body()).startsWith(item.field());
        }
        var unknownType = withQuestion(question("Когда?", "OTHER", "Утро", "Вечер"));
        problem(create(unknownType, teacherSession(), 400), "VALIDATION_FAILED");
        assertThat(count("select count(*) from poll")).isZero();
    }

    @Test
    void creationAcceptsInclusiveBoundaries() {
        var largest = pollBody("Т".repeat(200), NOW.plusSeconds(1), true, List.of(group, secondGroup), questions(20, 10));
        largest.put("description", "О".repeat(2000));
        var created = createPoll(largest);
        assertThat(created.path("questions")).hasSize(20);
        assertThat(created.path("questions").get(19).path("options")).hasSize(10);
        assertThat(ids(created.path("groups"))).containsExactlyInAnyOrder(group, secondGroup);
        var smallest = pollBody("Т", ENDS, false, List.of(group),
                List.of(question("В".repeat(500), "MULTIPLE", "Д".repeat(200), "Н")));
        smallest.put("description", "");
        var minimal = createPoll(smallest);
        assertThat(minimal.path("description").asText()).isEmpty();
        assertThat(texts(minimal.path("questions").get(0).path("options"))).containsExactly("Д".repeat(200), "Н");
    }

    @Test
    void groupsOutsideTeacherScopeAreForbiddenAndNothingIsCreated() {
        problem(create(formatPoll(true, otherGroup), teacherSession(), 403), "GROUP_NOT_ACCESSIBLE");
        problem(create(formatPoll(true, group, otherGroup), teacherSession(), 403), "GROUP_NOT_ACCESSIBLE");
        drainEvents();
        assertThat(count("select count(*) from poll")).isZero();
        assertThat(count("select count(*) from event_publication where event_type=?", PollCreated.class.getName())).isZero();
        assertThat(count("select count(*) from user_event where recipient_id in (?,?)", student, outsider)).isZero();

        update("insert into teacher_grant(teacher_id,kind,group_id) values (?,'GROUP',?)", teacher, otherGroup);
        var granted = create(formatPoll(true, group, otherGroup), teacherSession(), 201);
        assertThat(ids(granted.path("groups"))).containsExactlyInAnyOrder(group, otherGroup);
        assertThat(granted.path("eligible").asInt()).isEqualTo(7);
    }

    @Test
    void onlyApprovedTeacherCreates() {
        create(formatPoll(true, group), session("unapproved"), 403);
        create(formatPoll(true, group), studentSession(), 403);
        create(formatPoll(true, group), session("admin"), 403);
        create(formatPoll(true, group), null, 401);
        assertThat(count("select count(*) from poll")).isZero();
    }

    @Test
    void ae4OnlyAuthorAndTargetStudentsReadPoll() {
        long pollId = formatPoll(true).path("id").asLong();
        problem(poll(pollId, session("otherTeacher"), 403), "FORBIDDEN");
        problem(poll(pollId, session("admin"), 403), "FORBIDDEN");
        problem(poll(pollId, session("outsider"), 403), "FORBIDDEN");
        problem(poll(pollId, session("neighbour"), 403), "FORBIDDEN");
        problem(poll(Long.MAX_VALUE, studentSession(), 404), "NOT_FOUND");
        problem(poll(Long.MAX_VALUE, teacherSession(), 404), "NOT_FOUND");
        poll(pollId, null, 401);
        poll(pollId, teacherSession(), 200);
        poll(pollId, classmateSession(), 200);
        request(get(POLLS), session("admin"), 403);
        request(get(POLLS), null, 401);
    }

    @Test
    void pendingStudentOfTargetGroupSeesOpenPollWithoutResults() {
        long pollId = formatPoll(true).path("id").asLong();
        var view = poll(pollId, session("pending"), 200);
        assertThat(view.path("open").asBoolean()).isTrue();
        assertThat(view.path("voted").asBoolean()).isFalse();
        assertThat(view.path("results").isNull()).isTrue();
        assertThat(view.path("eligible").isNull()).isTrue();
        assertThat(ids(request(get(POLLS), session("pending"), 200).path("items"))).containsExactly(pollId);
    }

    @Test
    void listsAreScopedAndOrderedOpenAscendingThenClosedDescendingThenId() {
        long first = poll("Первый", NOW.plus(Duration.ofHours(1)));
        long second = poll("Второй", NOW.plus(Duration.ofHours(2)));
        long third = poll("Третий", NOW.plus(Duration.ofHours(3)));
        long fourth = poll("Четвёртый", NOW.plus(Duration.ofHours(4)));
        long twin = poll("Близнец третьего", NOW.plus(Duration.ofHours(3)));
        long boundary = poll("Ровно сейчас", NOW.plus(Duration.ofMinutes(150)));
        long foreign = create(formatPoll(true, group), session("otherTeacher"), 201).path("id").asLong();
        long neighbourOnly = createPoll(formatPoll(true, secondGroup)).path("id").asLong();
        clock.set(NOW.plus(Duration.ofMinutes(150)));

        var studentList = request(get(POLLS), studentSession(), 200).path("items");
        assertThat(ids(studentList)).containsExactly(third, twin, fourth, foreign, boundary, second, first);
        var byId = byId(studentList);
        assertThat(byId.get(third).path("open").asBoolean()).isTrue();
        assertThat(byId.get(boundary).path("open").asBoolean()).isFalse();
        assertThat(byId.get(first).path("open").asBoolean()).isFalse();
        assertThat(byId.get(third).path("voted").asBoolean()).isFalse();
        assertThat(byId.get(third).path("voted").isBoolean()).isTrue();
        assertThat(byId.get(third).path("anonymous").asBoolean()).isTrue();
        assertThat(byId.get(third).path("title").asText()).isEqualTo("Третий");
        assertThat(Instant.parse(byId.get(third).path("endsAt").asText())).isEqualTo(NOW.plus(Duration.ofHours(3)));
        assertThat(ids(byId.get(third).path("groups"))).containsExactly(group);

        var teacherList = request(get(POLLS), teacherSession(), 200).path("items");
        assertThat(ids(teacherList)).containsExactly(third, twin, fourth, neighbourOnly, boundary, second, first);
        assertThat(byId(teacherList).get(third).path("voted").isNull()).isTrue();
        assertThat(ids(request(get(POLLS), session("otherTeacher"), 200).path("items"))).containsExactly(foreign);
        assertThat(ids(request(get(POLLS), session("neighbour"), 200).path("items"))).containsExactly(neighbourOnly);
        assertThat(request(get(POLLS), session("outsider"), 200).path("items")).isEmpty();
    }

    @Test
    void pollEndingExactlyNowIsClosed() {
        Instant end = NOW.plus(Duration.ofHours(1));
        long pollId = poll("Граница", end);
        clock.set(end.minusMillis(1));
        assertThat(poll(pollId, studentSession(), 200).path("open").asBoolean()).isTrue();
        clock.set(end);
        assertThat(poll(pollId, studentSession(), 200).path("open").asBoolean()).isFalse();
        assertThat(poll(pollId, teacherSession(), 200).path("open").asBoolean()).isFalse();
        assertThat(request(get(POLLS), studentSession(), 200).path("items").get(0).path("open").asBoolean()).isFalse();
    }

    @Test
    void pollCannotBeChangedOrDeletedAfterCreation() {
        var created = formatPoll(true);
        long pollId = created.path("id").asLong();
        var change = exchange(patch(POLLS + "/" + pollId).content("{\"title\":\"Другое\"}"), teacherSession());
        assertThat(change.status()).isGreaterThanOrEqualTo(400);
        var removal = exchange(delete(POLLS + "/" + pollId), teacherSession());
        assertThat(removal.status()).isGreaterThanOrEqualTo(400);
        var after = poll(pollId, teacherSession(), 200);
        assertThat(after.path("title").asText()).isEqualTo("Формат зачёта");
        assertThat(after.path("questions")).isEqualTo(created.path("questions"));
    }

    private long poll(String title, Instant endsAt) {
        return createPoll(pollBody(title, endsAt, true, List.of(group),
                List.of(question("Когда удобнее?", "SINGLE", "Утро", "Вечер")))).path("id").asLong();
    }

    private record Case(String field, Map<String, Object> body) {}

    private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
        body.put(key, value);
        return body;
    }

    private Map<String, Object> withQuestion(Map<String, Object> question) {
        return with(formatPoll(true, group), "questions", List.of(question));
    }

    private static String[] options(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(index -> "Вариант " + index).toArray(String[]::new);
    }

    private static List<Map<String, Object>> questions(int count, int options) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(index -> question("Вопрос " + index, index % 2 == 0 ? "MULTIPLE" : "SINGLE", options(options)))
                .toList();
    }

    private static List<String> texts(JsonNode options) {
        var result = new ArrayList<String>();
        options.forEach(option -> result.add(option.path("text").asText()));
        return result;
    }

    private static Map<Long, JsonNode> byId(JsonNode items) {
        var result = new HashMap<Long, JsonNode>();
        items.forEach(item -> result.put(item.path("id").asLong(), item));
        return result;
    }
}

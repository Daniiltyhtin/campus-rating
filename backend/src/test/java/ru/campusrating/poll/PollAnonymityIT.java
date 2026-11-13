package ru.campusrating.poll;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class PollAnonymityIT extends PollTestSupport {
    private static final Set<String> CONTRACT_FIELDS = Set.of("items", "id", "title", "description", "endsAt", "open",
            "anonymous", "groups", "name", "faculty", "code", "author", "fullName", "questions", "text", "type", "options",
            "voted", "eligible", "results", "questionId", "optionId", "votes", "share");

    @Autowired LoggingSystem logging;

    @Test
    void schemaHasNoTableLinkingChoiceToStudentExceptNamedChoice() {
        assertThat(pollTables()).containsExactlyInAnyOrderElementsOf(POLL_TABLES);
        for (String table : pollTables()) {
            if (table.equals("poll_named_choice")) {
                continue;
            }
            var columns = columns(table);
            var targets = references(table);
            boolean choice = targets.contains("poll_option") || columns.stream().anyMatch(column -> column.contains("option")
                    || column.contains("choice") || column.contains("answer"));
            boolean person = targets.contains("app_user") || targets.contains("poll_participation")
                    || columns.stream().anyMatch(column -> column.contains("student") || column.contains("user")
                    || column.contains("participation") || column.contains("voter"));
            assertThat(choice && person).as("таблица %s: колонки %s, ссылки %s", table, columns, targets).isFalse();
        }
        assertThat(columns("poll_participation")).noneMatch(column -> column.contains("option") || column.contains("question")
                || column.contains("answer") || column.contains("choice"));
    }

    @Test
    void anonymousVotesAddOnlyParticipationRowsAndNamedPollAddsChoices() {
        var anonymous = formatPoll(true);
        long anonymousId = anonymous.path("id").asLong();
        var named = formatPoll(false);
        long namedId = named.path("id").asLong();
        var before = rowCounts();
        vote(anonymousId, studentSession(), 200, answer(questionId(anonymous, 0), optionId(anonymous, 0, 1)));
        vote(anonymousId, classmateSession(), 200, answer(questionId(anonymous, 0), optionId(anonymous, 0, 0)));
        var after = rowCounts();
        for (String table : POLL_TABLES) {
            int expected = before.get(table) + (table.equals("poll_participation") ? 2 : 0);
            assertThat(after.get(table)).as("строки в %s после анонимных голосов", table).isEqualTo(expected);
        }
        assertThat(count("select count(*) from poll_named_choice where poll_id=?", anonymousId)).isZero();

        vote(namedId, studentSession(), 200, answer(questionId(named, 0), optionId(named, 0, 1)));
        vote(namedId, classmateSession(), 200, answer(questionId(named, 0), optionId(named, 0, 0)));
        assertThat(count("select count(*) from poll_named_choice where poll_id=?", namedId)).isEqualTo(2);
        assertThat(count("select count(*) from poll_named_choice where poll_id=?", anonymousId)).isZero();
    }

    @Test
    void responsesOfAnonymousPollCarryNoStudentChoice() {
        var created = formatPoll(true);
        long pollId = created.path("id").asLong();
        long when = questionId(created, 0), evening = optionId(created, 0, 1);
        var bodies = new ArrayList<JsonNode>();
        bodies.add(vote(pollId, studentSession(), 200, answer(when, evening)));
        bodies.add(poll(pollId, studentSession(), 200));
        bodies.add(request(get(POLLS), studentSession(), 200));
        bodies.add(poll(pollId, teacherSession(), 200));
        bodies.add(request(get(POLLS), teacherSession(), 200));
        var responses = request(get(POLLS + "/" + pollId + "/responses"), teacherSession(), 409);
        problem(responses, "INVALID_STATE");
        for (var body : bodies) {
            assertThat(fieldNames(body)).as("поля ответа %s", body).isSubsetOf(CONTRACT_FIELDS);
            assertThat(body.toString()).doesNotContainPattern(token(student));
        }
        assertThat(responses.toString()).doesNotContainPattern(token(student)).doesNotContain("Вечер");
    }

    @Test
    void debugLogsOfVotingDoNotPairStudentWithOption(CapturedOutput output) {
        String morning = "Утро-" + UUID.randomUUID().toString().substring(0, 8);
        String evening = "Вечер-" + UUID.randomUUID().toString().substring(0, 8);
        var created = createPoll(pollBody("Формат зачёта", ENDS, true, List.of(group),
                List.of(question("Когда удобнее?", "SINGLE", morning, evening))));
        long pollId = created.path("id").asLong();
        long when = questionId(created, 0), morningId = optionId(created, 0, 0), eveningId = optionId(created, 0, 1);
        var session = studentSession();
        poll(pollId, session, 200);
        var root = logging.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME);
        LogLevel previous = root == null || root.getConfiguredLevel() == null ? LogLevel.INFO : root.getConfiguredLevel();
        int start = output.getAll().length();
        logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.DEBUG);
        logging.setLogLevel("org.springframework.web.client", LogLevel.INFO);
        try {
            vote(pollId, session, 400, answer(when, morningId, eveningId));
            vote(pollId, session, 200, answer(when, eveningId));
            vote(pollId, session, 409, answer(when, morningId));
            drainEvents();
        } finally {
            logging.setLogLevel("org.springframework.web.client", null);
            logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, previous);
        }
        String captured = output.getAll().substring(start);
        assertThat(captured).as("захват логов DEBUG пуст").isNotBlank();
        var studentToken = token(student);
        var optionTokens = List.of(optionToken(morningId), optionToken(eveningId));
        for (String line : captured.split("\\R")) {
            if (!studentToken.matcher(line).find()) {
                continue;
            }
            assertThat(line).as("строка лога с id студента").doesNotContain(morning).doesNotContain(evening)
                    .doesNotContain("optionIds");
            for (var option : optionTokens) {
                assertThat(option.matcher(line).find()).as("id варианта рядом с id студента: %s", line).isFalse();
            }
        }
        assertThat(captured).doesNotContain("\"optionIds\"", "optionIds=");
    }

    private List<String> pollTables() {
        return jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = current_schema() and (table_name = 'poll' or table_name like 'poll\\_%')
                order by table_name
                """, String.class);
    }

    private List<String> columns(String table) {
        return jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_schema = current_schema() and table_name = ? order by ordinal_position
                """, String.class, table);
    }

    private Set<String> references(String table) {
        return new HashSet<>(jdbc.queryForList("""
                select distinct target.relname from pg_constraint c
                join pg_class source on source.oid = c.conrelid
                join pg_class target on target.oid = c.confrelid
                join pg_namespace n on n.oid = source.relnamespace
                where c.contype = 'f' and n.nspname = current_schema() and source.relname = ?
                """, String.class, table));
    }

    private Map<String, Integer> rowCounts() {
        var result = new LinkedHashMap<String, Integer>();
        for (String table : POLL_TABLES) {
            result.put(table, count("select count(*) from " + table));
        }
        return result;
    }

    private static Set<String> fieldNames(JsonNode node) {
        var result = new HashSet<String>();
        collect(node, result);
        return result;
    }

    private static void collect(JsonNode node, Set<String> names) {
        if (node.isObject()) {
            for (var property : node.properties()) {
                names.add(property.getKey());
                collect(property.getValue(), names);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, names));
        }
    }

    private static Pattern token(long id) {
        return Pattern.compile("(?<!\\d)" + id + "(?!\\d)");
    }

    private static Pattern optionToken(long id) {
        return Pattern.compile("(?<![\\d:.\\-])" + id + "(?![\\d:.])");
    }
}

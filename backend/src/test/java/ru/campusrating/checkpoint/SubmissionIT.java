package ru.campusrating.checkpoint;

import ru.campusrating.support.UserNameFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import ru.campusrating.identity.AuthenticatedUser;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.Role;
import ru.campusrating.ledger.Balances;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.shared.storage.FileStorage;
import ru.campusrating.support.MutableClock;
import ru.campusrating.support.PostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

class SubmissionIT extends PostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @Autowired MutableClock clock;

    @Value("${campus.storage.dir}")
    Path storage;

    @MockitoBean CurrentUser current;
    @MockitoBean LedgerApi ledger;
    @MockitoSpyBean FileStorage files;
    MockMvc mvc;
    final JsonMapper json = new JsonMapper();
    long teacher, student, other, thirdStudent, faculty, group, checkpoint, assignment, second;

    @BeforeEach
    void fixture() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        teacher = person("TEACHER");
        student = person("STUDENT");
        other = person("STUDENT");
        thirdStudent = person("STUDENT");
        faculty =
                jdbc.queryForObject(
                        "insert into faculty(code,name) values (?,'Факультет') returning id",
                        Long.class,
                        UUID.randomUUID().toString());
        group =
                jdbc.queryForObject(
                        "insert into study_group(code,name,faculty_id,admission_year) values"
                            + " (?,'ИВТ',?,2026) returning id",
                        Long.class,
                        UUID.randomUUID().toString(),
                        faculty);
        checkpoint = point();
        assignment = assign(checkpoint, student);
        second = assign(checkpoint, other);
        doReturn(new Balances(15, 0, 0)).when(ledger).balances(anyLong());
        actor(student, Role.STUDENT);
    }

    @AfterEach
    void cleanup() throws Exception {
        jdbc.execute("drop trigger if exists reject_submission on submission");
        jdbc.execute("drop function if exists reject_submission_test()");
        var keys =
                jdbc.queryForList(
                        "select file_key from submission where assignment_id in (select id from"
                            + " assignment where checkpoint_id in (select id from checkpoint where"
                            + " author_id=?)) and file_key is not null",
                        String.class,
                        teacher);
        for (String key : keys) {
            Files.deleteIfExists(storage.resolve(key));
        }
        jdbc.update(
                "delete from submission where assignment_id in (select id from assignment where"
                    + " checkpoint_id in (select id from checkpoint where author_id=?))",
                teacher);
        jdbc.update(
                "delete from assignment where checkpoint_id in (select id from checkpoint where"
                    + " author_id=?)",
                teacher);
        jdbc.update("delete from checkpoint where author_id=?", teacher);
        jdbc.update("delete from study_group where id=?", group);
        jdbc.update("delete from faculty where id=?", faculty);
        jdbc.update(
                "delete from app_user where id in (?,?,?,?)",
                teacher,
                student,
                other,
                thirdStudent);
        verify(ledger, never()).post(anyList());
    }

    @Test
    void overdueLinkStopsPenaltyAndRepeatedSubmissionConflicts() throws Exception {
        var initial = get(assignment, 200);
        assertThat(initial.path("status").asText()).isEqualTo("OVERDUE");
        assertThat(initial.at("/penalty/nextAmount").asInt()).isEqualTo(15);
        assertThat(initial.path("submission").isNull()).isTrue();
        assertThat(initial.path("submissions").size()).isZero();
        download(assignment, "submission/file", 404);
        var submitted = link(assignment, "https://example.org/work", 200);
        assertThat(submitted.path("state").asText()).isEqualTo("SUBMITTED");
        assertThat(submitted.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(submitted.at("/penalty/phase").asText()).isEqualTo("STOPPED");
        assertThat(submitted.at("/penalty/nextAt").isNull()).isTrue();
        assertThat(link(assignment, "https://example.org/new", 409).path("detail").asText())
                .isEqualTo("Работа уже сдана");
        download(assignment, "submission/file", 404);
        actor(teacher, Role.TEACHER);
        accept(List.of(assignment), 200);
        actor(student, Role.STUDENT);
        assertThat(link(assignment, "https://example.org/new", 409).path("detail").asText())
                .isEqualTo("Работа уже принята");
    }

    @Test
    void readsPenaltyFromStoredSlotsLedgerBalanceAndClock() throws Exception {
        var now = clock.instant();
        var deadline = now.minusSeconds(90);
        clock.set(deadline);
        var before = get(assignment, 200);
        assertThat(before.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(before.at("/penalty/phase").asText()).isEqualTo("BEFORE_DEADLINE");
        assertThat(before.at("/penalty/nextAt").asText()).isEqualTo(deadline.toString());
        assertThat(before.at("/penalty/nextAmount").asInt()).isEqualTo(20);
        clock.set(now);
        jdbc.update("update assignment set charged=40,last_penalty_slot=1 where id=?", assignment);
        var running = get(assignment, 200);
        assertThat(running.at("/penalty/charged").asInt()).isEqualTo(40);
        assertThat(running.at("/penalty/phase").asText()).isEqualTo("RUNNING");
        assertThat(running.at("/penalty/nextAt").asText()).isEqualTo(deadline.plusSeconds(120).toString());
        assertThat(running.at("/penalty/nextAmount").asInt()).isEqualTo(15);
        doReturn(new Balances(0, 100, 100)).when(ledger).balances(student);
        var zero = get(assignment, 200);
        assertThat(zero.at("/penalty/phase").asText()).isEqualTo("ZERO_BALANCE");
        assertThat(zero.at("/penalty/nextAmount").asInt()).isZero();
        jdbc.update("update assignment set charged=60 where id=?", assignment);
        var capped = get(assignment, 200);
        assertThat(capped.at("/penalty/phase").asText()).isEqualTo("CAP_REACHED");
        assertThat(capped.at("/penalty/nextAt").isNull()).isTrue();
    }

    @Test
    void returnPreservesPenaltyAndHistoryThenResubmissionClearsCurrentComment() throws Exception {
        jdbc.update("update assignment set charged=40,last_penalty_slot=1 where id=?", assignment);
        var first = link(assignment, "https://example.org/work", 200).path("submission");
        actor(teacher, Role.TEACHER);
        request("POST", assignment, "return", "{}", 400);
        request("POST", assignment, "return", "{\"comment\":\" \"}", 400);
        request(
                "POST",
                assignment,
                "return",
                json.writeValueAsString(Map.of("comment", "x".repeat(2001))),
                400);
        var returned = request("POST", assignment, "return", "{\"comment\":\"Нет тестов\"}", 200);
        assertThat(returned.path("state").asText()).isEqualTo("OPEN");
        assertThat(returned.path("returnComment").asText()).isEqualTo("Нет тестов");
        assertThat(returned.at("/submission/id").asLong()).isEqualTo(first.path("id").asLong());
        assertThat(returned.at("/penalty/charged").asInt()).isEqualTo(40);
        assertThat(
                        jdbc.queryForObject(
                                "select last_penalty_slot from assignment where id=?",
                                Integer.class,
                                assignment))
                .isEqualTo(1);
        assertThat(
                        request("POST", assignment, "return", "{\"comment\":\"Ещё\"}", 409)
                                .path("detail")
                                .asText())
                .isEqualTo("Вернуть можно только сданную работу");
        actor(student, Role.STUDENT);
        assertThat(get(assignment, 200).path("returnComment").asText()).isEqualTo("Нет тестов");
        var result = file(assignment, "отчёт.pdf", pdf(9 * 1024 * 1024), 200);
        assertThat(result.path("returnComment").isNull()).isTrue();
        assertThat(result.path("submissions").size()).isEqualTo(2);
        assertThat(result.at("/submissions/1/returnComment").asText()).isEqualTo("Нет тестов");
        assertThat(result.at("/submissions/1/returnedAt").isNull()).isFalse();
        long version = result.at("/submission/id").asLong();
        var response = download(assignment, "submissions/" + version + "/file", 200);
        assertThat(response.getContentAsByteArray()).isEqualTo(pdf(9 * 1024 * 1024));
        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(response.getHeader("Content-Disposition"))
                .contains("attachment; filename=\"", "filename*=UTF-8''%D0");
        download(assignment, "submissions/" + first.path("id").asLong() + "/file", 404);
    }

    @Test
    void oldFileRemainsAvailableAfterNewFileSubmission() throws Exception {
        long version = file(assignment, "report.pdf", pdf(32), 200).at("/submission/id").asLong();
        actor(teacher, Role.TEACHER);
        request("POST", assignment, "return", "{\"comment\":\"Исправьте\"}", 200);
        actor(student, Role.STUDENT);
        file(assignment, "new.pdf", pdf(64), 200);
        assertThat(
                        download(assignment, "submissions/" + version + "/file", 200)
                                .getContentAsByteArray())
                .isEqualTo(pdf(32));
        assertThat(download(assignment, "submission/file", 200).getContentAsByteArray())
                .isEqualTo(pdf(64));
    }

    @Test
    void validatesMultipartPartsLinkSizeExtensionAndSignature() throws Exception {
        perform(MockMvcRequestBuilders.multipart(path(assignment, "submission")), 400);
        perform(
                MockMvcRequestBuilders.multipart(path(assignment, "submission"))
                        .file(upload("report.pdf", pdf(32)))
                        .param("link", "https://example.org"),
                400);
        for (String invalid :
                List.of("ftp://example.org", " ", "https://example.org/" + "x".repeat(2000))) {
            assertThat(link(assignment, invalid, 400).path("fieldErrors").toString())
                    .contains("link");
        }
        assertThat(file(assignment, "report.pdf", pdf(11 * 1024 * 1024), 413).path("code").asText())
                .isEqualTo("FILE_TOO_LARGE");
        assertThat(file(assignment, "bad.exe", pdf(32), 415).path("code").asText())
                .isEqualTo("FILE_TYPE_NOT_ALLOWED");
        file(assignment, "fake.pdf", new byte[20], 415);
        assertThat(get(assignment, 200).path("state").asText()).isEqualTo("OPEN");
    }

    @Test
    void acceptanceDeduplicatesSortsAndSkipsInvalidStates() throws Exception {
        long third = assign(checkpoint, thirdStudent);
        link(assignment, "https://example.org/a", 200);
        actor(other, Role.STUDENT);
        link(second, "https://example.org/b", 200);
        actor(teacher, Role.TEACHER);
        var result = accept(List.of(second, third, assignment, second), 200);
        assertThat(result.path("accepted").toString())
                .isEqualTo(json.writeValueAsString(List.of(assignment, second)));
        assertThat(result.at("/skipped/0/assignmentId").asLong()).isEqualTo(third);
        assertThat(result.at("/skipped/0/code").asText()).isEqualTo("INVALID_STATE");
        var repeated = accept(List.of(second, assignment), 200);
        assertThat(repeated.path("accepted").size()).isZero();
        assertThat(repeated.at("/skipped/0/assignmentId").asLong()).isEqualTo(assignment);
        assertThat(repeated.at("/skipped/1/assignmentId").asLong()).isEqualTo(second);
        assertThat(get(assignment, 200).path("acceptedAt").isNull()).isFalse();
        jdbc.update("delete from assignment where id=?", third);
    }

    @Test
    void invalidAcceptanceIsAtomic() throws Exception {
        link(assignment, "https://example.org", 200);
        long foreign = assign(point(), student);
        actor(teacher, Role.TEACHER);
        assertThat(accept(List.of(), 400).path("fieldErrors").toString())
                .contains("assignmentIds", "Выберите хотя бы одну работу");
        for (long invalid : List.of(foreign, Long.MAX_VALUE)) {
            assertThat(accept(List.of(assignment, invalid), 400).path("fieldErrors").toString())
                    .contains("Есть работы не из этой точки");
            assertThat(get(assignment, 200).path("state").asText()).isEqualTo("SUBMITTED");
        }
    }

    @Test
    void accessUsesAssignmentOwnerAndCheckpointAuthor() throws Exception {
        long version = file(assignment, "report.pdf", pdf(32), 200).at("/submission/id").asLong();
        actor(other, Role.STUDENT);
        get(assignment, 403);
        download(assignment, "submission/file", 403);
        download(assignment, "submissions/" + version + "/file", 403);
        download(second, "submissions/" + version + "/file", 404);
        link(assignment, "https://example.org", 403);
        actor(other, Role.TEACHER);
        get(assignment, 403);
        download(assignment, "submission/file", 403);
        accept(List.of(assignment), 403);
        request("POST", assignment, "return", "{\"comment\":\"Нет\"}", 403);
        for (Role role : List.of(Role.TEACHER, Role.ADMIN)) {
            actor(teacher, role);
            assertThat(get(assignment, 200).path("submissions").size()).isEqualTo(1);
            download(assignment, "submission/file", 200);
            download(assignment, "submissions/" + version + "/file", 200);
        }
        accept(List.of(assignment), 403);
        request("POST", assignment, "return", "{\"comment\":\"Нет\"}", 403);
    }

    @Test
    void simultaneousSubmissionsProduceOneVersion() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks =
                    IntStream.range(0, 2)
                            .mapToObj(
                                    i ->
                                            executor.submit(
                                                    () -> {
                                                        barrier.await(10, TimeUnit.SECONDS);
                                                        return mvc.perform(
                                                                        MockMvcRequestBuilders
                                                                                .multipart(
                                                                                        path(
                                                                                                assignment,
                                                                                                "submission"))
                                                                                .param(
                                                                                        "link",
                                                                                        "https://example.org/"
                                                                                                + i)
                                                                                .with(
                                                                                        user("tester")
                                                                                                .roles(
                                                                                                        "STUDENT"))
                                                                                .with(csrf()))
                                                                .andReturn()
                                                                .getResponse()
                                                                .getStatus();
                                                    }))
                            .toList();
            assertThat(
                            List.of(
                                    tasks.get(0).get(10, TimeUnit.SECONDS),
                                    tasks.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(get(assignment, 200).path("submissions").size()).isEqualTo(1);
    }

    @Test
    void databaseFailureAfterFileSaveRemovesFileOnRollback() throws Exception {
        List<Path> before;
        try (var files = Files.list(storage)) {
            before = files.toList();
        }
        jdbc.execute(
                "create function reject_submission_test() returns trigger language plpgsql as $$"
                    + " begin raise exception 'forced failure'; end $$");
        jdbc.execute(
                "create trigger reject_submission before insert on submission for each row execute"
                    + " function reject_submission_test()");
        var savedKey = new AtomicReference<String>();
        doAnswer(
                        invocation -> {
                            String key = (String) invocation.callRealMethod();
                            savedKey.set(key);
                            assertThat(storage.resolve(key)).exists();
                            return key;
                        })
                .when(files)
                .save(any(), any());
        file(assignment, "report.pdf", pdf(32), 500);
        assertThat(savedKey.get()).isNotNull();
        assertThat(storage.resolve(savedKey.get())).doesNotExist();
        try (var files = Files.list(storage)) {
            assertThat(files.toList()).containsExactlyInAnyOrderElementsOf(before);
        }
        assertThat(get(assignment, 200).path("submissions").size()).isZero();
        assertThat(get(assignment, 200).path("state").asText()).isEqualTo("OPEN");
    }

    void actor(long id, Role role) {
        doReturn(new AuthenticatedUser(id, role, "Тест", false, true, null))
                .when(current)
                .require();
        activeRole = role;
    }

    Role activeRole;

    long person(String role) {
        return jdbc.queryForObject("insert into app_user(email,role,last_name,first_name,middle_name) values (?,?,?,?,?) returning id",
                Long.class,
                UUID.randomUUID() + "@test.invalid",
                role,
                UserNameFixture.last("Тест"),
                UserNameFixture.first("Тест"),
                UserNameFixture.middle("Тест"));
    }

    long point() {
        return jdbc.queryForObject(
                "insert into"
                    + " checkpoint(author_id,title,description,deadline,cost,penalty_step,penalty_interval_seconds,created_at,updated_at)"
                    + " values (?,'Работа','Описание',?,60,20,60,?,?) returning id",
                Long.class,
                teacher,
                Timestamp.from(clock.instant().minusSeconds(90)),
                Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()));
    }

    long assign(long point, long owner) {
        return jdbc.queryForObject(
                "insert into"
                    + " assignment(checkpoint_id,student_id,group_id,student_full_name,state,created_at)"
                    + " values (?,?,?,'Студент','OPEN',?) returning id",
                Long.class,
                point,
                owner,
                group,
                Timestamp.from(clock.instant()));
    }

    byte[] pdf(int size) {
        byte[] bytes = new byte[size];
        System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 5);
        return bytes;
    }

    MockMultipartFile upload(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }

    JsonNode file(long id, String name, byte[] bytes, int status) throws Exception {
        return perform(
                MockMvcRequestBuilders.multipart(path(id, "submission")).file(upload(name, bytes)),
                status);
    }

    JsonNode link(long id, String link, int status) throws Exception {
        return perform(
                MockMvcRequestBuilders.multipart(path(id, "submission")).param("link", link),
                status);
    }

    JsonNode accept(List<Long> ids, int status) throws Exception {
        return perform(
                MockMvcRequestBuilders.post(
                                "/api/v1/checkpoints/" + checkpoint + "/assignments/accept")
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("assignmentIds", ids))),
                status);
    }

    JsonNode get(long id, int status) throws Exception {
        return request("GET", id, "", null, status);
    }

    JsonNode request(String method, long id, String suffix, String body, int status)
            throws Exception {
        var builder = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path(id, suffix));
        if (body != null) {
            builder.contentType("application/json").content(body);
        }
        return perform(builder, status);
    }

    JsonNode perform(AbstractMockHttpServletRequestBuilder<?> builder, int status)
            throws Exception {
        var response =
                mvc.perform(builder.with(user("tester").roles(activeRole.name())).with(csrf()))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return json.readTree(response.getContentAsString());
    }

    MockHttpServletResponse download(long id, String suffix, int status) throws Exception {
        var response =
                mvc.perform(
                                MockMvcRequestBuilders.get(path(id, suffix))
                                        .with(user("tester").roles(activeRole.name())))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        return response;
    }

    String path(long id, String suffix) {
        return "/api/v1/assignments/" + id + (suffix.isEmpty() ? "" : "/" + suffix);
    }
}

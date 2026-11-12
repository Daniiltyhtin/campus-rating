package ru.campusrating.ledger;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import ru.campusrating.identity.AuthenticatedUser;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.Role;
import ru.campusrating.ledger.domain.LedgerCursor;
import ru.campusrating.support.MutableClock;
import ru.campusrating.support.PostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** Приёмка U4 (мастер-U10): listLedgerEntries поверх LedgerApi.history (П9, KTD7, мастер-R6). */
class LedgerEntriesIT extends PostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @Autowired MutableClock clock;
    @Autowired LedgerApi ledger;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean CurrentUser current;
    final JsonMapper json = new JsonMapper();
    final List<Long> people = new ArrayList<>();
    final List<Long> points = new ArrayList<>();
    MockMvc mvc;
    Instant now;
    long student, otherStudent, teacher, faculty, group;
    Role role;

    @BeforeEach
    void fixture() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        now = clock.instant();
        student = person(Role.STUDENT, "Смирнова Анна Олеговна");
        otherStudent = person(Role.STUDENT, "Иванов Иван Иванович");
        teacher = person(Role.TEACHER, "Петров Павел Петрович");
        faculty = id("insert into faculty(code,name) values (?,'Факультет') returning id", UUID.randomUUID().toString());
        group = id("insert into study_group(code,name,faculty_id,admission_year) values (?,'ИВТ-21',?,2026) returning id",
                UUID.randomUUID().toString(), faculty);
        actor(otherStudent, Role.STUDENT);
    }

    @AfterEach
    void cleanup() {
        for (long point : points) {
            jdbc.update("delete from checkpoint_group where checkpoint_id=?", point);
            jdbc.update("delete from checkpoint where id=?", point);
        }
        for (long id : people) {
            jdbc.update("delete from ledger_entry where student_id=?", id);
            jdbc.update("delete from ledger_balance where student_id=?", id);
        }
        for (long id : people) {
            jdbc.update("delete from app_user where id=?", id);
        }
        jdbc.update("delete from study_group where id=?", group);
        jdbc.update("delete from faculty where id=?", faculty);
    }

    @Test
    void awardShowsTeacherAuthorAndPenaltyShowsCheckpointSourceWithSystemAuthor() throws Exception {
        long initial = posted(post(student, Currency.AR, EntryKind.INITIAL, 1000, new Source(SourceType.REGISTRATION, null, "Стартовые баллы"), null));
        tick(60);
        long award = posted(post(student, Currency.BOA, EntryKind.ACTIVITY_AWARD, 50,
                new Source(SourceType.ACTIVITY, 601L, "Помощь в организации конференции"),
                new Author(teacher, "Петров Павел Петрович")));
        tick(60);
        long point = point("Лабораторная 3. Деревья поиска");
        long penalty = posted(post(student, Currency.AR, EntryKind.PENALTY, -20, new Source(SourceType.CHECKPOINT, point, "Лабораторная 3. Деревья поиска"), null));

        var page = entries(student, "", 200);

        assertThat(ids(page)).containsExactly(penalty, award, initial);
        assertThat(page.path("nextCursor").isNull()).isTrue();
        var first = page.at("/items/0");
        assertThat(first.path("createdAt").asText()).isEqualTo(now.plusSeconds(120).toString());
        assertThat(first.path("currency").asText()).isEqualTo("AR");
        assertThat(first.path("amount").asInt()).isEqualTo(-20);
        assertThat(first.path("kind").asText()).isEqualTo("PENALTY");
        assertThat(first.at("/source/type").asText()).isEqualTo("CHECKPOINT");
        assertThat(first.at("/source/id").asLong()).isEqualTo(point);
        assertThat(first.at("/source/title").asText()).isEqualTo("Лабораторная 3. Деревья поиска");
        assertThat(first.has("author")).isTrue();
        assertThat(first.path("author").isNull()).isTrue();
        var second = page.at("/items/1");
        assertThat(second.path("createdAt").asText()).isEqualTo(now.plusSeconds(60).toString());
        assertThat(second.path("currency").asText()).isEqualTo("BOA");
        assertThat(second.path("amount").asInt()).isEqualTo(50);
        assertThat(second.path("kind").asText()).isEqualTo("ACTIVITY_AWARD");
        assertThat(second.path("source"))
                .isEqualTo(json.readTree("{\"type\":\"ACTIVITY\",\"id\":601,\"title\":\"Помощь в организации конференции\"}"));
        assertThat(second.path("author"))
                .isEqualTo(json.readTree("{\"id\":" + teacher + ",\"fullName\":\"Петров Павел Петрович\"}"));
        var third = page.at("/items/2");
        assertThat(third.path("kind").asText()).isEqualTo("INITIAL");
        assertThat(third.at("/source/type").asText()).isEqualTo("REGISTRATION");
        assertThat(third.at("/source/id").isNull()).isTrue();
        assertThat(third.path("author").isNull()).isTrue();
    }

    @Test
    void penaltyShowsActualClippedAmount() throws Exception {
        post(student, Currency.AR, EntryKind.INITIAL, 30, new Source(SourceType.REGISTRATION, null, "Стартовые баллы"), null);
        tick(1);
        long point = point("Работа");
        post(student, Currency.AR, EntryKind.PENALTY, -40, new Source(SourceType.CHECKPOINT, point, "Работа"), null);
        assertThat(entries(student, "", 200).at("/items/0/amount").asInt()).isEqualTo(-30);
    }

    @Test
    void historyKeepsSnapshotsAfterCheckpointRenameAndTeacherNameChange() throws Exception {
        post(student, Currency.AR, EntryKind.INITIAL, 1000, new Source(SourceType.REGISTRATION, null, "Стартовые баллы"), null);
        tick(1);
        long point = point("Лабораторная 3. Деревья поиска");
        post(student, Currency.AR, EntryKind.PENALTY, -20, new Source(SourceType.CHECKPOINT, point, "Лабораторная 3. Деревья поиска"), null);
        tick(1);
        post(student, Currency.BUA, EntryKind.ACTIVITY_AWARD, 38, new Source(SourceType.ACTIVITY, 601L, "Олимпиада"),
                new Author(teacher, "Петров Павел Петрович"));
        var before = entries(student, "", 200);

        jdbc.update("update checkpoint set title='Лабораторная 3 (новое название)' where id=?", point);
        jdbc.update("update app_user set full_name='Петров-Сидоров Павел Петрович' where id=?", teacher);
        var after = entries(student, "", 200);

        assertThat(after).isEqualTo(before);
        assertThat(after.at("/items/0/author/fullName").asText()).isEqualTo("Петров Павел Петрович");
        assertThat(after.at("/items/0/author/id").asLong()).isEqualTo(teacher);
        assertThat(after.at("/items/1/source/title").asText()).isEqualTo("Лабораторная 3. Деревья поиска");
        assertThat(after.toString()).doesNotContain("новое название", "Петров-Сидоров");
    }

    @Test
    void currencyFilterReturnsOnlyThatCurrency() throws Exception {
        seedMixedCurrencies(student);
        for (Currency currency : Currency.values()) {
            var page = entries(student, "currency=" + currency, 200);
            assertThat(page.path("items")).isNotEmpty();
            assertThat(StreamSupport.stream(page.path("items").spliterator(), false).map(item -> item.path("currency").asText()))
                    .containsOnly(currency.name());
            assertThat(ids(page)).containsExactlyElementsOf(expectedIds(student, Optional.of(currency)));
        }
        assertThat(ids(entries(student, "", 200))).containsExactlyElementsOf(expectedIds(student, Optional.empty()));
    }

    @Test
    void paginatesOneHundredTwentyEntriesWithoutDuplicatesOrGaps() throws Exception {
        for (int i = 0; i < 120; i++) {
            // Три проводки на одну секунду: порядок внутри секунды задаёт id.
            if (i % 3 == 0) {
                tick(1);
            }
            post(student, i % 2 == 0 ? Currency.BUA : Currency.BOA, EntryKind.ACTIVITY_AWARD, i + 1,
                    new Source(SourceType.ACTIVITY, (long) i + 1, "Активность " + i), new Author(teacher, "Петров Павел Петрович"));
        }
        var expected = expectedIds(student, Optional.empty());
        assertThat(expected).hasSize(120);

        var collected = new ArrayList<Long>();
        var sizes = new ArrayList<Integer>();
        String cursor = null;
        int pages = 0;
        do {
            var page = entries(student, "limit=50" + (cursor == null ? "" : "&cursor=" + cursor), 200);
            sizes.add(page.path("items").size());
            collected.addAll(ids(page));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
            assertThat(++pages).isLessThanOrEqualTo(3);
        } while (cursor != null);

        assertThat(sizes).containsExactly(50, 50, 20);
        assertThat(collected).doesNotHaveDuplicates().containsExactlyElementsOf(expected);

        var defaultPage = entries(student, "", 200);
        assertThat(ids(defaultPage)).containsExactlyElementsOf(expected.subList(0, 20));
        assertThat(defaultPage.path("nextCursor").isNull()).isFalse();

        var buaCollected = new ArrayList<Long>();
        cursor = null;
        do {
            var page = entries(student, "currency=BUA&limit=25" + (cursor == null ? "" : "&cursor=" + cursor), 200);
            buaCollected.addAll(ids(page));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        } while (cursor != null);
        assertThat(buaCollected).doesNotHaveDuplicates()
                .containsExactlyElementsOf(expectedIds(student, Optional.of(Currency.BUA))).hasSize(60);
    }

    @Test
    void unknownTeacherAndEntrylessIdsGiveEmptyPage() throws Exception {
        long removed = person(Role.STUDENT, "Удалённый");
        jdbc.update("delete from app_user where id=?", removed);
        for (long userId : List.of(removed, Long.MAX_VALUE, teacher, student)) {
            var page = entries(userId, "", 200);
            assertThat(page.path("items").isArray()).isTrue();
            assertThat(page.path("items")).isEmpty();
            assertThat(page.has("nextCursor")).isTrue();
            assertThat(page.path("nextCursor").isNull()).isTrue();
        }
    }

    @Test
    void anyAuthenticatedRoleReadsAnyStudentHistory() throws Exception {
        seedMixedCurrencies(student);
        int size = expectedIds(student, Optional.empty()).size();
        for (var viewer : List.of(Role.STUDENT, Role.TEACHER, Role.ADMIN)) {
            actor(viewer == Role.STUDENT ? student : viewer == Role.TEACHER ? teacher : otherStudent, viewer);
            assertThat(entries(student, "", 200).path("items")).hasSize(size);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"broken", "bm90LWEtY3Vyc29y", "aGlzdG9yeS8xLzE"})
    void brokenCursorIsRejected(String cursor) throws Exception {
        seedMixedCurrencies(student);
        assertCursorRejected(entries(student, "cursor=" + cursor, 400));
    }

    @Test
    void emptyCursorIsRejectedByContractMinLength() throws Exception {
        assertCursorRejected(entries(student, "cursor=", 400));
    }

    @Test
    void wellFormedCursorWithTimestampOutsideRangeIsRejected() throws Exception {
        String filter = LedgerCursor.historyFilter(student, Optional.empty());
        for (Instant at : List.of(Instant.parse("1969-12-31T23:59:59Z"), Instant.parse("+10000-01-01T00:00:00Z"))) {
            long micros = at.getEpochSecond() * 1_000_000;
            String cursor = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((filter + ":" + micros + ":1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertCursorRejected(entries(student, "cursor=" + cursor, 400));
        }
    }

    @Test
    void cursorOfAnotherUserIsRejected() throws Exception {
        seedMixedCurrencies(student);
        seedMixedCurrencies(otherStudent);
        String foreign = entries(otherStudent, "limit=1", 200).path("nextCursor").asText();
        assertThat(foreign).isNotBlank();
        assertCursorRejected(entries(student, "limit=1&cursor=" + foreign, 400));
        entries(otherStudent, "limit=1&cursor=" + foreign, 200);
    }

    @Test
    void cursorOfAnotherCurrencyFilterIsRejected() throws Exception {
        seedMixedCurrencies(student);
        String bua = entries(student, "currency=BUA&limit=1", 200).path("nextCursor").asText();
        String all = entries(student, "limit=1", 200).path("nextCursor").asText();
        assertThat(bua).isNotBlank();
        assertThat(all).isNotBlank();
        assertCursorRejected(entries(student, "currency=BOA&limit=1&cursor=" + bua, 400));
        assertCursorRejected(entries(student, "limit=1&cursor=" + bua, 400));
        assertCursorRejected(entries(student, "currency=AR&limit=1&cursor=" + all, 400));
        entries(student, "currency=BUA&limit=1&cursor=" + bua, 200);
        entries(student, "limit=1&cursor=" + all, 200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1", "x"})
    void limitOutsideOneToHundredIsRejected(String limit) throws Exception {
        var problem = entries(student, "limit=" + limit, 400);
        assertThat(problem.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(fieldsOf(problem)).contains("limit");
    }

    @Test
    void limitBoundariesOneAndHundredAreAccepted() throws Exception {
        for (int i = 0; i < 101; i++) {
            if (i % 10 == 0) {
                tick(1);
            }
            post(student, Currency.BOA, EntryKind.ACTIVITY_AWARD, 1, new Source(SourceType.ACTIVITY, (long) i + 1, "Активность"),
                    new Author(teacher, "Петров Павел Петрович"));
        }
        var one = entries(student, "limit=1", 200);
        assertThat(one.path("items")).hasSize(1);
        assertThat(one.path("nextCursor").isNull()).isFalse();
        var hundred = entries(student, "limit=100", 200);
        assertThat(hundred.path("items")).hasSize(100);
        assertThat(hundred.path("nextCursor").isNull()).isFalse();
        var rest = entries(student, "limit=100&cursor=" + hundred.path("nextCursor").asText(), 200);
        assertThat(rest.path("items")).hasSize(1);
        assertThat(rest.path("nextCursor").isNull()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"XYZ", "ar"})
    void unknownCurrencyIsRejected(String currency) throws Exception {
        var problem = entries(student, "currency=" + currency, 400);
        assertThat(problem.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(fieldsOf(problem)).contains("currency");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    void invalidUserIdIsRejected(String userId) throws Exception {
        var problem = request(MockMvcRequestBuilders.get("/api/v1/ledger/" + userId + "/entries"), true, 400);
        assertThat(problem.path("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void guestIsUnauthenticated() throws Exception {
        seedMixedCurrencies(student);
        var problem = request(MockMvcRequestBuilders.get("/api/v1/ledger/" + student + "/entries"), false, 401);
        assertThat(problem.path("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    void assertCursorRejected(JsonNode problem) {
        assertThat(problem.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(fieldsOf(problem)).contains("cursor");
    }

    List<String> fieldsOf(JsonNode problem) {
        return StreamSupport.stream(problem.path("fieldErrors").spliterator(), false)
                .map(error -> error.path("field").asText()).toList();
    }

    /** По две проводки каждой валюты, с разным временем. */
    void seedMixedCurrencies(long owner) {
        post(owner, Currency.AR, EntryKind.INITIAL, 1000, new Source(SourceType.REGISTRATION, null, "Стартовые баллы"), null);
        tick(1);
        long point = point("Работа");
        post(owner, Currency.AR, EntryKind.PENALTY, -20, new Source(SourceType.CHECKPOINT, point, "Работа"), null);
        var author = new Author(teacher, "Петров Павел Петрович");
        for (int i = 0; i < 2; i++) {
            tick(1);
            post(owner, Currency.BUA, EntryKind.ACTIVITY_AWARD, 10 + i, new Source(SourceType.ACTIVITY, 700L + i, "Олимпиада"), author);
            tick(1);
            post(owner, Currency.BOA, EntryKind.ACTIVITY_AWARD, 20 + i, new Source(SourceType.ACTIVITY, 800L + i, "Конференция"), author);
        }
    }

    List<Long> expectedIds(long owner, Optional<Currency> currency) {
        return currency.isPresent()
                ? jdbc.queryForList("select id from ledger_entry where student_id=? and currency=? order by created_at desc, id desc",
                        Long.class, owner, currency.get().name())
                : jdbc.queryForList("select id from ledger_entry where student_id=? order by created_at desc, id desc", Long.class, owner);
    }

    List<Long> ids(JsonNode page) {
        return StreamSupport.stream(page.path("items").spliterator(), false).map(item -> item.path("id").asLong()).toList();
    }

    JsonNode entries(long userId, String query, int status) throws Exception {
        return request(MockMvcRequestBuilders.get("/api/v1/ledger/" + userId + "/entries" + (query.isEmpty() ? "" : "?" + query)), true, status);
    }

    JsonNode request(MockHttpServletRequestBuilder builder, boolean signedIn, int status) throws Exception {
        if (signedIn) {
            builder.with(user("tester").roles(role.name()));
        }
        var response = mvc.perform(builder).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        String body = response.getContentAsString();
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }

    void actor(long id, Role nextRole) {
        role = nextRole;
        var user = new AuthenticatedUser(id, nextRole, "Смотрящий", false, true, null);
        doReturn(user).when(current).require();
        doReturn(Optional.of(user)).when(current).find();
    }

    void tick(long seconds) {
        clock.set(clock.instant().plusSeconds(seconds));
    }

    PostingResult post(long owner, Currency currency, EntryKind kind, int amount, Source source, Author author) {
        return new TransactionTemplate(transactions).execute(ignored -> ledger.post(List.of(
                new Posting(owner, currency, kind, amount, source, author, UUID.randomUUID().toString()))).getFirst());
    }

    long posted(PostingResult result) {
        return ((PostingResult.Posted) result).entryId();
    }

    long person(Role personRole, String name) {
        long id = id("insert into app_user(email,role,full_name) values (?,?,?) returning id",
                UUID.randomUUID() + "@test.invalid", personRole.name(), name);
        people.add(id);
        return id;
    }

    long point(String title) {
        long id = id("insert into checkpoint(author_id,title,description,deadline,cost,penalty_step,penalty_interval_seconds,created_at,updated_at) values (?,?,'Описание',?,60,20,60,?,?) returning id",
                teacher, title, Timestamp.from(now.minusSeconds(3600)), Timestamp.from(now.minusSeconds(7200)), Timestamp.from(now.minusSeconds(7200)));
        jdbc.update("insert into checkpoint_group(checkpoint_id,group_id) values (?,?)", id, group);
        points.add(id);
        return id;
    }

    long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}

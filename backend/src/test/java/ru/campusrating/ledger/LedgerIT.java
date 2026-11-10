package ru.campusrating.ledger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.campusrating.shared.error.ValidationFailedException;
import ru.campusrating.support.MutableClock;
import ru.campusrating.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class LedgerIT extends PostgresIntegrationTest {
    @Autowired private LedgerApi ledger;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MutableClock clock;
    @LocalServerPort private int port;
    private final List<Long> users = new ArrayList<>();
    private long student;
    private long author;

    @BeforeEach
    void createUsers() {
        student = user("STUDENT");
        author = user("TEACHER");
    }

    @AfterEach
    void cleanUp() {
        for (long user : users) {
            jdbc.update("delete from ledger_entry where student_id = ? or author_id = ?", user, user);
            jdbc.update("delete from ledger_balance where student_id = ?", user);
        }
        users.forEach(user -> jdbc.update("delete from app_user where id = ?", user));
    }

    @Test
    void initialAndRepeatedKeyReturnOriginalEntryWithoutChangingBalance() {
        var initial = initial(student, "initial");
        var posted = (PostingResult.Posted) post(initial).getFirst();
        assertThat(posted.amount()).isEqualTo(1000);
        assertThat(posted.balanceAfter()).isEqualTo(1000);
        assertThat(ledger.balances(student)).isEqualTo(new Balances(1000, 0, 0));
        post(penalty(student, -20, "penalty"));
        assertThat(post(initial)).containsExactly(new PostingResult.AlreadyPosted(posted.entryId(), 1000));
        assertThat(ledger.balances(student).ar()).isEqualTo(980);
        assertThat(entryCount()).isEqualTo(2);
    }

    @Test
    void clipsAtBothArLimitsAndPreservesBatchOrder() {
        var result = post(ar(student, EntryKind.INITIAL, 900, "first"),
                ar(student, EntryKind.SEMESTER_ACCRUAL, 200, "second"),
                ar(student, EntryKind.SEMESTER_ACCRUAL, 200, "zero"));
        assertThat(((PostingResult.Posted) result.get(0)).amount()).isEqualTo(900);
        assertThat(((PostingResult.Posted) result.get(1)).amount()).isEqualTo(100);
        assertThat(result.get(2)).isEqualTo(new PostingResult.Zero());
        assertThat(entryCount()).isEqualTo(2);
        var floor = post(penalty(student, -990, "down"), penalty(student, -20, "floor"), penalty(student, -20, "floor-zero"));
        assertThat(((PostingResult.Posted) floor.get(1)).amount()).isEqualTo(-10);
        assertThat(floor.get(2)).isEqualTo(new PostingResult.Zero());
        post(ar(student, EntryKind.SEMESTER_ACCRUAL, 200, "refill"));
        assertThat(((PostingResult.Posted) post(penalty(student, -20, "next-slot")).getFirst()).amount()).isEqualTo(-20);
        assertThat(ledger.balances(student).ar()).isEqualTo(180);
    }

    @Test
    void duplicateWithinBatchUsesFirstResult() {
        var posting = award(student, Currency.BUA, 50, "same");
        var result = post(posting, posting);
        var first = (PostingResult.Posted) result.getFirst();
        assertThat(result.get(1)).isEqualTo(new PostingResult.AlreadyPosted(first.entryId(), 50));
        assertThat(ledger.balances(student).bua()).isEqualTo(50);
    }

    @Test
    void reusedKeyWithDifferentStudentCurrencyOrKindRollsBackBatch() {
        long other = user("STUDENT");
        post(ar(student, EntryKind.INITIAL, 100, "kind-key"), award(student, Currency.BUA, 10, "award-key"));
        var mismatches = List.of(award(other, Currency.BUA, 10, "award-key"),
                award(student, Currency.BOA, 10, "award-key"),
                ar(student, EntryKind.SEMESTER_ACCRUAL, 100, "kind-key"));
        for (var mismatch : mismatches) {
            assertThatIllegalArgumentException().isThrownBy(() -> post(
                    award(other, Currency.BOA, 1, "must-rollback"), mismatch));
            assertThat(ledger.balances(student)).isEqualTo(new Balances(100, 10, 0));
            assertThat(ledger.balances(other)).isEqualTo(new Balances(0, 0, 0));
            assertThat(entryCount()).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from ledger_entry where student_id = ?", Integer.class, other)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from ledger_balance where student_id = ?", Integer.class, other)).isZero();
        }
    }

    @Test
    void concurrentFirstAwardsCreateBalanceWithoutLosingUpdates() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from ledger_balance where student_id = ?", Integer.class, student)).isZero();
        var barrier = new CyclicBarrier(2);
        var results = concurrently(
                () -> racingPost(barrier, List.of(award(student, Currency.BUA, 10, "first-ten"))),
                () -> racingPost(barrier, List.of(award(student, Currency.BUA, 20, "first-twenty"))));
        assertThat(results).allSatisfy(batch -> assertThat(batch).singleElement().isInstanceOf(PostingResult.Posted.class));
        assertThat(ledger.balances(student)).isEqualTo(new Balances(0, 30, 0));
        assertThat(entryCount()).isEqualTo(2);
        assertThat(ledger.reconcile().mismatches()).isEmpty();
    }

    @Test
    void sameKeyRaceCreatesExactlyOnePosting() throws Exception {
        post(ar(student, EntryKind.INITIAL, 900, "race-seed"));
        var barrier = new CyclicBarrier(2);
        var posting = ar(student, EntryKind.SEMESTER_ACCRUAL, 100, "race");
        var results = concurrently(() -> racingPost(barrier, List.of(posting)), () -> racingPost(barrier, List.of(posting)));
        assertThat(results.stream().map(List::getFirst).filter(PostingResult.Posted.class::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().map(List::getFirst).filter(PostingResult.AlreadyPosted.class::isInstance).count()).isEqualTo(1);
        assertThat(entryCount()).isEqualTo(2);
        assertThat(ledger.balances(student).ar()).isEqualTo(1000);
    }

    @Test
    void reverseStudentAndCurrencyOrdersDoNotDeadlock() throws Exception {
        long other = user("STUDENT");
        post(award(student, Currency.BUA, 1, "seed-a-bua"), award(student, Currency.BOA, 1, "seed-a-boa"),
                award(other, Currency.BUA, 1, "seed-b-bua"), award(other, Currency.BOA, 1, "seed-b-boa"));
        var barrier = new CyclicBarrier(2);
        var first = List.of(award(student, Currency.BUA, 10, "a1"), award(other, Currency.BOA, 20, "b1"),
                award(student, Currency.BOA, 30, "a2"), award(other, Currency.BUA, 40, "b2"));
        var reversed = List.of(award(other, Currency.BUA, 40, "b3"), award(student, Currency.BOA, 30, "a3"),
                award(other, Currency.BOA, 20, "b4"), award(student, Currency.BUA, 10, "a4"));
        var results = concurrently(() -> racingPost(barrier, first), () -> racingPost(barrier, reversed));
        assertThat(results).allSatisfy(batch -> assertThat(batch).hasSize(4).allMatch(PostingResult.Posted.class::isInstance));
        assertThat(ledger.balances(student)).isEqualTo(new Balances(0, 21, 61));
        assertThat(ledger.balances(other)).isEqualTo(new Balances(0, 81, 41));
        assertThat(ledger.reconcile().mismatches()).isEmpty();
    }

    @Test
    void concurrentAwardsOnExistingBalanceDoNotLoseUpdates() throws Exception {
        post(award(student, Currency.BOA, 50, "lost-update-seed"));
        int beforeEntries = entryCount();
        long gateKey = -student;
        // До вставки проводки баланс уже прочитан, но UPDATE ещё не может скрыть отсутствие FOR UPDATE.
        jdbc.execute("""
                create function u6_lost_update_gate() returns trigger language plpgsql as $$
                begin
                    perform pg_advisory_xact_lock(%d);
                    return NEW;
                end;
                $$
                """.formatted(gateKey));
        try {
            jdbc.execute("""
                    create trigger u6_lost_update_gate before insert on ledger_entry
                    for each row when (NEW.student_id = %d) execute function u6_lost_update_gate()
                    """.formatted(student));
            try (var gate = jdbc.getDataSource().getConnection(); var executor = Executors.newFixedThreadPool(2)) {
                try (var statement = gate.prepareStatement("select pg_advisory_lock(?)")) {
                    statement.setLong(1, gateKey);
                    statement.execute();
                }
                var barrier = new CyclicBarrier(2);
                var firstPid = new CompletableFuture<Integer>();
                var secondPid = new CompletableFuture<Integer>();
                var first = executor.submit(() -> racingPost(barrier,
                        List.of(award(student, Currency.BOA, 10, "lost-update-ten")), firstPid));
                var second = executor.submit(() -> racingPost(barrier,
                        List.of(award(student, Currency.BOA, 20, "lost-update-twenty")), secondPid));
                try {
                    int a = firstPid.get(10, TimeUnit.SECONDS);
                    int b = secondPid.get(10, TimeUnit.SECONDS);
                    // Штатно один запрос ждёт барьер, второй — строку; без FOR UPDATE оба ждут барьер.
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                            select count(*) from pg_stat_activity
                            where pid in (?, ?) and wait_event_type = 'Lock'
                            """, Integer.class, a, b)).isEqualTo(2));
                } finally {
                    try (var statement = gate.prepareStatement("select pg_advisory_unlock(?)")) {
                        statement.setLong(1, gateKey);
                        statement.execute();
                    }
                }
                assertThat(first.get(20, TimeUnit.SECONDS)).singleElement().isInstanceOf(PostingResult.Posted.class);
                assertThat(second.get(20, TimeUnit.SECONDS)).singleElement().isInstanceOf(PostingResult.Posted.class);
            }
        } finally {
            jdbc.execute("drop trigger if exists u6_lost_update_gate on ledger_entry");
            jdbc.execute("drop function u6_lost_update_gate()");
        }
        assertThat(ledger.balances(student).boa()).isEqualTo(80);
        assertThat(entryCount() - beforeEntries).isEqualTo(2);
        assertThat(sum(student, Currency.BOA)).isEqualTo(80);
        assertThat(ledger.reconcile().mismatches()).isEmpty();
    }

    @Test
    void mandatoryTransactionAndValidationPrecedeWrites() {
        assertThatThrownBy(() -> ledger.post(List.of(initial(student, "outside"))))
                .isInstanceOf(IllegalTransactionStateException.class);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThatIllegalArgumentException().isThrownBy(() -> ledger.post(List.of(initial(student, "valid"),
                    ar(student, EntryKind.PENALTY, 20, "invalid"))));
            assertThat(entryCount()).isZero();
            assertThat(jdbc.queryForObject("select count(*) from ledger_balance where student_id = ?", Integer.class, student)).isZero();
            status.setRollbackOnly();
        });
        assertThat(post()).isEmpty();
    }

    @Test
    void callerFailureRollsBackPostingAndBalance() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            ledger.post(List.of(initial(student, "rollback")));
            throw new IllegalStateException("caller failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(entryCount()).isZero();
        assertThat(ledger.balances(student)).isEqualTo(new Balances(0, 0, 0));
        assertThat(jdbc.queryForObject("select count(*) from ledger_balance where student_id = ?", Integer.class, student)).isZero();
    }

    @Test
    void databaseRejectsArOutsideRangeAndZeroEntries() {
        post(initial(student, "initial"));
        assertThatThrownBy(() -> jdbc.update("update ledger_balance set amount = 1001 where student_id = ? and currency = 'AR'", student))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update ledger_balance set amount = -1 where student_id = ? and currency = 'AR'", student))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update ledger_entry set amount = 0 where student_id = ?", student))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNegativeActivityBalances() {
        post(award(student, Currency.BUA, 10, "bua-seed"), award(student, Currency.BOA, 20, "boa-seed"));
        for (var currency : List.of(Currency.BUA, Currency.BOA)) {
            assertThatThrownBy(() -> jdbc.update(
                    "update ledger_balance set amount = -1 where student_id = ? and currency = ?", student, currency.name()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(ledger.balances(student)).isEqualTo(new Balances(0, 10, 20));
    }

    @Test
    void randomOperationsKeepSnapshotEqualToJournal() {
        long other = user("STUDENT");
        var random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            long id = random.nextBoolean() ? student : other;
            int amount = random.nextInt(1, 501);
            Posting posting = switch (random.nextInt(4)) {
                case 0 -> ar(id, EntryKind.SEMESTER_ACCRUAL, amount, "random-" + i);
                case 1 -> penalty(id, -amount, "random-" + i);
                case 2 -> award(id, Currency.BUA, amount, "random-" + i);
                default -> award(id, Currency.BOA, amount, "random-" + i);
            };
            post(posting);
        }
        assertThat(ledger.reconcile().mismatches()).isEmpty();
        assertThat(ledger.reconcile().checkedPairs()).isEqualTo(6);
        for (long id : List.of(student, other)) {
            var balances = ledger.balances(id);
            assertThat(balances.ar()).isBetween(0, 1000);
            assertThat(balances.ar()).isEqualTo(sum(id, Currency.AR));
            assertThat(balances.bua()).isEqualTo(sum(id, Currency.BUA));
            assertThat(balances.boa()).isEqualTo(sum(id, Currency.BOA));
        }
    }

    @Test
    void balancesIncludeMissingIdsAndReadSnapshotInsteadOfJournal() {
        long missing = Long.MAX_VALUE;
        assertThat(ledger.balances(missing)).isEqualTo(new Balances(0, 0, 0));
        assertThat(ledger.balances(List.of())).isEmpty();
        post(award(student, Currency.BOA, 50, "award"));
        jdbc.update("update ledger_balance set amount = 55 where student_id = ? and currency = 'BOA'", student);
        assertThat(ledger.balances(List.of(student, missing)))
                .containsEntry(student, new Balances(0, 0, 55)).containsEntry(missing, new Balances(0, 0, 0)).hasSize(2);
    }

    @Test
    void historyUsesStableCursorAndCurrencyFilterAndKeepsSnapshots() {
        for (int i = 0; i < 25; i++) {
            post(award(student, i % 2 == 0 ? Currency.BUA : Currency.BOA, 10, "history-" + i));
        }
        jdbc.update("update app_user set full_name = 'Новое имя' where id = ?", author);
        var first = ledger.history(student, Optional.empty(), Optional.empty(), 20);
        assertThat(first.items()).hasSize(20);
        assertThat(first.nextCursor()).isNotNull();
        var second = ledger.history(student, Optional.empty(), Optional.of(first.nextCursor()), 20);
        assertThat(second.items()).hasSize(5);
        assertThat(second.nextCursor()).isNull();
        var ids = new ArrayList<Long>();
        first.items().forEach(entry -> ids.add(entry.id()));
        second.items().forEach(entry -> ids.add(entry.id()));
        assertThat(ids).doesNotHaveDuplicates().isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(first.items()).allSatisfy(entry -> {
            assertThat(entry.createdAt()).isEqualTo(clock.instant());
            assertThat(entry.source().title()).isEqualTo("Снимок активности");
            assertThat(entry.author()).isEqualTo(new Author(author, "Снимок преподавателя"));
        });
        assertThat(ledger.history(student, Optional.of(Currency.BUA), Optional.empty(), 100).items())
                .hasSize(13).allMatch(entry -> entry.currency() == Currency.BUA);
        assertThat(ledger.history(Long.MAX_VALUE, Optional.empty(), Optional.empty(), 20).items()).isEmpty();
    }

    @Test
    void awardsExcludeOtherKindsAndPaginateAcrossStudentsAndTimestamps() {
        long other = user("STUDENT");
        post(initial(student, "initial"), penalty(student, -20, "penalty"), award(student, Currency.BUA, 10, "old-award"));
        clock.advance(Duration.ofSeconds(1));
        post(award(other, Currency.BOA, 20, "new-award"));
        var first = ledger.awards(Optional.empty(), 1);
        assertThat(first.items()).hasSize(1);
        assertThat(first.items().getFirst().studentId()).isEqualTo(other);
        var second = ledger.awards(Optional.of(first.nextCursor()), 1);
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().getFirst().studentId()).isEqualTo(student);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void invalidCursorIdentifiesCursorFieldForBothReaders() {
        assertCursorFailure(() -> ledger.history(student, Optional.empty(), Optional.of("broken"), 20));
        assertCursorFailure(() -> ledger.awards(Optional.of("broken"), 20));
    }

    @Test
    void readersRejectForeignFiltersAndOutOfRangeCursorTime() {
        post(initial(student, "cursor-initial"), penalty(student, -1, "cursor-penalty"),
                award(student, Currency.BOA, 1, "cursor-award"));
        var arCursor = ledger.history(student, Optional.of(Currency.AR), Optional.empty(), 1).nextCursor();
        assertThat(arCursor).isNotNull();
        assertCursorFailure(() -> ledger.history(student, Optional.of(Currency.BOA), Optional.of(arCursor), 1));
        assertCursorFailure(() -> ledger.history(student, Optional.empty(), Optional.of(arCursor), 1));
        assertCursorFailure(() -> ledger.history(author, Optional.of(Currency.AR), Optional.of(arCursor), 1));
        assertCursorFailure(() -> ledger.awards(Optional.of(arCursor), 1));
        var awardFilter = ru.campusrating.ledger.domain.LedgerCursor.AWARDS;
        var awardsCursor = new ru.campusrating.ledger.domain.LedgerCursor(clock.instant(), 1, awardFilter).encode();
        assertCursorFailure(() -> ledger.history(student, Optional.empty(), Optional.of(awardsCursor), 1));
        for (var micros : List.of(Long.MIN_VALUE, Long.MAX_VALUE)) {
            var encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (awardFilter + ":" + micros + ":1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertCursorFailure(() -> ledger.awards(Optional.of(encoded), 1));
        }
    }

    @Test
    void readersValidateLimitAndAcceptBoundaries() {
        post(award(student, Currency.BOA, 10, "limit-seed"));
        for (int limit : List.of(0, 101)) {
            assertValidationFailure(() -> ledger.history(student, Optional.empty(), Optional.empty(), limit), "limit");
            assertValidationFailure(() -> ledger.awards(Optional.empty(), limit), "limit");
        }
        for (int limit : List.of(1, 100)) {
            assertThat(ledger.history(student, Optional.empty(), Optional.empty(), limit).items()).hasSize(1);
            assertThat(ledger.awards(Optional.empty(), limit).items()).hasSize(1);
        }
    }

    @Test
    void reconciliationFindsDriftAndCountsUnionIncludingMissingSides() {
        assertThat(ledger.reconcile().checkedPairs()).isZero();
        post(initial(student, "initial"), award(student, Currency.BOA, 50, "award"));
        assertThat(ledger.reconcile().checkedPairs()).isEqualTo(2);
        assertThat(ledger.reconcile().mismatches()).isEmpty();
        jdbc.update("update ledger_balance set amount = 960 where student_id = ? and currency = 'AR'", student);
        assertThat(ledger.reconcile().mismatches()).containsExactly(new BalanceMismatch(student, Currency.AR, 960, 1000));
        jdbc.update("delete from ledger_balance where student_id = ? and currency = 'BOA'", student);
        jdbc.update("insert into ledger_balance (student_id, currency, amount) values (?, 'BUA', 25)", student);
        var result = ledger.reconcile();
        assertThat(result.checkedPairs()).isEqualTo(3);
        assertThat(result.mismatches()).containsExactlyInAnyOrder(new BalanceMismatch(student, Currency.AR, 960, 1000),
                new BalanceMismatch(student, Currency.BOA, 0, 50), new BalanceMismatch(student, Currency.BUA, 25, 0));
    }

    @Test
    void reconciliationHttpMatchesContractAndUsesClock() throws Exception {
        post(initial(student, "initial"));
        String password = "Synthetic-admin-test-42";
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(password);
        jdbc.update("update app_user set role='ADMIN', password_hash=? where id=?", hash, author);
        String email = jdbc.queryForObject("select email from app_user where id=?", String.class, author);
        var cookies = new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL);
        var http = java.net.http.HttpClient.newBuilder().cookieHandler(cookies).build();
        var base = "http://127.0.0.1:" + port;
        var csrf = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/v1/auth/csrf"))
                .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(csrf.statusCode()).isEqualTo(204);
        String token = cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .findFirst().orElseThrow().getValue();
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var login = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/v1/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        java.util.Map.of("email", email, "password", password)))).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        var client = RestTestClient.bindToServer(new org.springframework.http.client.JdkClientHttpRequestFactory(http))
                .baseUrl(base).build();
        client.get().uri("/api/v1/admin/ledger/reconciliation").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.checkedAt").isEqualTo(clock.instant().toString())
                .jsonPath("$.checkedPairs").isEqualTo(1).jsonPath("$.mismatches").isEmpty();
    }

    private void assertCursorFailure(Runnable read) {
        assertValidationFailure(read, "cursor");
    }

    private void assertValidationFailure(Runnable read, String field) {
        assertThatThrownBy(read::run).isInstanceOfSatisfying(ValidationFailedException.class,
                exception -> assertThat(exception.fieldErrors()).singleElement().satisfies(error -> {
                    assertThat(error.field()).isEqualTo(field);
                    assertThat(error.code()).isEqualTo("INVALID");
                }));
    }

    private List<List<PostingResult>> concurrently(Callable<List<PostingResult>> first, Callable<List<PostingResult>> second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(first);
            var b = executor.submit(second);
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    private List<PostingResult> racingPost(CyclicBarrier barrier, List<Posting> postings) {
        return racingPost(barrier, postings, new CompletableFuture<>());
    }

    private List<PostingResult> racingPost(CyclicBarrier barrier, List<Posting> postings, CompletableFuture<Integer> backendPid) {
        return new TransactionTemplate(transactions).execute(status -> {
            jdbc.execute("set local lock_timeout = '10s'");
            backendPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            return ledger.post(postings);
        });
    }

    private List<PostingResult> post(Posting... postings) {
        return new TransactionTemplate(transactions).execute(status -> ledger.post(List.of(postings)));
    }

    private Posting initial(long id, String key) { return ar(id, EntryKind.INITIAL, 1000, key); }
    private Posting penalty(long id, int amount, String key) { return ar(id, EntryKind.PENALTY, amount, key); }

    private Posting ar(long id, EntryKind kind, int amount, String key) {
        var type = switch (kind) {
            case INITIAL -> SourceType.REGISTRATION;
            case SEMESTER_ACCRUAL -> SourceType.SEMESTER;
            case PENALTY -> SourceType.CHECKPOINT;
            case ACTIVITY_AWARD -> SourceType.ACTIVITY;
        };
        return new Posting(id, Currency.AR, kind, amount, new Source(type, 42L, "Снимок точки"), null, key);
    }

    private Posting award(long id, Currency currency, int amount, String key) {
        return new Posting(id, currency, EntryKind.ACTIVITY_AWARD, amount,
                new Source(SourceType.ACTIVITY, 42L, "Снимок активности"), new Author(author, "Снимок преподавателя"), key);
    }

    private long user(String role) {
        long id = jdbc.queryForObject("insert into app_user (email, role, full_name) values (?, ?, ?) returning id",
                Long.class, UUID.randomUUID() + "@example.test", role, "Пользователь");
        users.add(id);
        return id;
    }

    private int entryCount() {
        return jdbc.queryForObject("select count(*) from ledger_entry where student_id = ?", Integer.class, student);
    }

    private int sum(long id, Currency currency) {
        return jdbc.queryForObject("select coalesce(sum(amount), 0) from ledger_entry where student_id = ? and currency = ?",
                Integer.class, id, currency.name());
    }
}

package ru.campusrating.admin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.campusrating.support.AdminHttpTestSupport;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.test.context.TestPropertySource(properties="campus.semester.scheduler.enabled=true")
class U13AdminSemestersIT extends AdminHttpTestSupport {
    @org.springframework.beans.factory.annotation.Autowired private ru.campusrating.ledger.LedgerApi ledger;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    private static final String SEMESTERS = "/api/v1/admin/semesters";
    private static final String SETTINGS = "/api/v1/admin/settings";
    private final List<Long> created = new ArrayList<>();
    private List<Map<String, Object>> savedSettings;

    @BeforeEach void settingsSnapshot() {
        savedSettings = jdbc.queryForList("select key,value,type from settings");
        clock.set(Instant.parse("2030-11-13T09:00:00Z"));
    }
    @AfterEach void restore() {
        for (long id : created) {
            jdbc.update("delete from semester_accrual where semester_id=?", id);
            jdbc.update("delete from semester where id=?", id);
        }
        jdbc.update("delete from activity_budget where teacher_id in (select id from app_user where email like ?)", prefix + "%");
        jdbc.update("delete from settings");
        for (var row : savedSettings) jdbc.update("insert into settings(key,value,type) values (?,?,?)", row.get("key"), row.get("value"), row.get("type"));
        jdbc.update("delete from semester_accrual where student_id in (select id from app_user where email like ?)", prefix + "%");
    }
    private JsonNode create(String suffix, String start) {
        var result = request(admin, "POST", SEMESTERS, Map.of("title", prefix + suffix, "startsAt", start), 201);
        created.add(result.path("id").asLong());
        return result;
    }
    private Map<String, Object> settings(int step, int interval, int accrual, int limit) {
        return Map.of("penaltyStep", step, "penaltyIntervalSeconds", interval, "semesterAccrual", accrual, "monthlyBudgetLimit", limit);
    }
    @Test void r2r3r8CreationTrimsAndListsInChronologicalOrder() {
        var first = create(" first ", "2031-02-08T21:00:00Z");
        assertThat(first.path("title").asText()).isEqualTo(prefix + " first");
        assertThat(first.path("started").asBoolean()).isFalse();
        assertThat(first.path("accrual").isNull()).isTrue();
        var second = create(" second", "2031-06-01T00:00:00Z");
        assertThat(items(get(SEMESTERS)).stream().map(n -> Instant.parse(n.path("startsAt").asText())).toList()).isSorted();
        assertThat(ids(get(SEMESTERS))).contains(first.path("id").asLong(), second.path("id").asLong());
        invalid("POST", SEMESTERS, Map.of("title", prefix.toUpperCase() + " FIRST", "startsAt", "2032-01-01T00:00:00Z"), "title");
        invalid("POST", SEMESTERS, Map.of("title", prefix + " middle", "startsAt", "2031-03-01T00:00:00Z"), "startsAt");
    }
    @Test void r3RejectsNowPastBlankAndOverlongTitles() {
        for (String date : List.of(clock.instant().toString(), clock.instant().minusSeconds(60).toString()))
            invalid("POST", SEMESTERS, Map.of("title", prefix, "startsAt", date), "startsAt");
        for (String title : List.of("  ", "x".repeat(101)))
            invalid("POST", SEMESTERS, Map.of("title", title, "startsAt", "2032-01-01T00:00:00Z"), "title");
    }
    @Test void r4EditPreservesNeighboursAndDeleteRemovesScheduledSemester() {
        long first = create(" first", "2031-02-01T00:00:00Z").path("id").asLong();
        long second = create(" second", "2031-06-01T00:00:00Z").path("id").asLong();
        request(admin, "PUT", SEMESTERS + "/" + second, Map.of("title", prefix + " renamed", "startsAt", "2031-06-02T00:00:00Z"), 200);
        invalid("PUT", SEMESTERS + "/" + first, Map.of("title", prefix + " first", "startsAt", "2031-06-03T00:00:00Z"), "startsAt");
        invalid("PUT", SEMESTERS + "/" + second, Map.of("title", prefix + " second", "startsAt", "2031-02-01T00:00:00Z"), "startsAt");
        request(admin, "DELETE", SEMESTERS + "/" + second, null, 204);
        assertThat(ids(get(SEMESTERS))).doesNotContain(second);
    }
    @Test void r4r5r6OnlyNearestStartsAndAccrualIsFrozen() {
        request(admin, "PUT", SETTINGS, settings(4, 60, 200, 1000), 200);
        long first = create(" first", "2031-02-01T00:00:00Z").path("id").asLong();
        long second = create(" second", "2031-06-01T00:00:00Z").path("id").asLong();
        var rejected = request(admin, "POST", SEMESTERS + "/" + second + "/start", null, 409);
        assertThat(rejected.path("detail").asText()).isEqualTo("Начать сейчас можно только ближайший запланированный семестр");
        var started = request(admin, "POST", SEMESTERS + "/" + first + "/start", null, 200);
        assertThat(started.path("started").asBoolean()).isTrue();
        assertThat(Instant.parse(started.path("startsAt").asText())).isEqualTo(clock.instant());
        assertThat(started.path("accrual").asInt()).isEqualTo(200);
        for (String method : List.of("PUT", "DELETE")) {
            var failure = request(admin, method, SEMESTERS + "/" + first,
                    method.equals("PUT") ? Map.of("title", prefix, "startsAt", "2032-01-01T00:00:00Z") : null, 409);
            assertThat(failure.path("detail").asText()).isEqualTo("Семестр уже начался — изменить нельзя");
        }
        request(admin, "PUT", SETTINGS, settings(4, 60, 100, 1000), 200);
        assertThat(items(get(SEMESTERS)).stream().filter(n -> n.path("id").asLong() == first).findFirst().orElseThrow().path("accrual").asInt()).isEqualTo(200);
        assertThat(request(admin, "POST", SEMESTERS + "/" + second + "/start", null, 200).path("accrual").asInt()).isEqualTo(100);
    }
    @ParameterizedTest
    @CsvSource({"0,60,200,1000,penaltyStep", "1001,60,200,1000,penaltyStep", "4,1,200,1000,penaltyIntervalSeconds", "4,86401,200,1000,penaltyIntervalSeconds", "4,60,-1,1000,semesterAccrual", "4,60,1001,1000,semesterAccrual", "4,60,200,-1,monthlyBudgetLimit", "4,60,200,100001,monthlyBudgetLimit"})
    void r9InvalidSettingsAreAtomic(int step, int interval, int accrual, int limit, String field) {
        var before = get(SETTINGS);
        invalid("PUT", SETTINGS, settings(step, interval, accrual, limit), field);
        assertThat(get(SETTINGS)).isEqualTo(before);
    }
    @Test void r9InclusiveBoundsAndMinimumIntervalAreExposed() {
        assertThat(get(SETTINGS).path("minPenaltyIntervalSeconds").asInt()).isEqualTo(2);
        for (var value : List.of(settings(1, 2, 0, 0), settings(1000, 86400, 1000, 100000))) {
            request(admin, "PUT", SETTINGS, value, 200);
            var actual = get(SETTINGS);
            value.forEach((key, expected) -> assertThat(actual.path(key).asInt()).isEqualTo(expected));
        }
        var fractional = new java.util.HashMap<String, Object>(settings(4,60,200,1000));
        fractional.put("penaltyStep", 1.5);
        invalid("PUT", SETTINGS, fractional, "penaltyStep");
    }
    @Test void r11r12BudgetsUseCurrentLimitAndImpactCountsPairsAboveNewLimit() {
        long teacher = teacher("budget", "Budget teacher");
        jdbc.update("insert into activity_budget(teacher_id,group_id,month,spent) values (?,?,'2030-11-01',450),(?,?,'2030-11-01',300),(?,?,'2030-10-01',700)", teacher, group, teacher, sameNameGroup, teacher, group);
        request(admin, "PUT", SETTINGS, settings(4,60,200,1000), 200);
        assertThat(get("/api/v1/admin/budgets/impact?limit=300").path("count").asInt()).isEqualTo(1);
        request(admin, "PUT", SETTINGS, settings(4,60,200,300), 200);
        var rows = items(get("/api/v1/admin/budgets?month=2030-11"));
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.at("/teacher/id").asLong()).isEqualTo(teacher);
            assertThat(row.path("limit").asInt()).isEqualTo(300);
            assertThat(row.path("remaining").asInt()).isZero();
        });
        assertThat(items(get("/api/v1/admin/budgets?month=2030-10"))).singleElement().satisfies(row -> assertThat(row.path("limit").asInt()).isEqualTo(300));
        assertThat(items(get("/api/v1/admin/budgets?month=2030-09"))).isEmpty();
        assertThat(get("/api/v1/admin/budgets")).isEqualTo(get("/api/v1/admin/budgets?month=2030-11"));
    }
    @Test void r7ScheduledAccrualCapsAtThousandRemembersZeroAndExcludesLateRegistration() {
        long capped=student("capped",faculty,group);
        long zero=student("zero",faculty,group);
        adjust(capped,-100);
        request(admin,"PUT",SETTINGS,settings(4,60,200,1000),200);
        long semester=create(" capped", "2031-02-01T00:00:00Z").path("id").asLong();
        request(admin,"POST",SEMESTERS+"/"+semester+"/start",null,200);
        clock.set(clock.instant().plusSeconds(1));
        long late=student("late",faculty,group); adjust(late,-100);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(90)).untilAsserted(() -> {
            assertThat(ar(capped)).isEqualTo(1000);
            assertThat(jdbc.queryForObject("select count(*) from semester_accrual where semester_id=? and student_id in (?,?)",Integer.class,semester,capped,zero)).isEqualTo(2);
        });
        assertThat(jdbc.queryForObject("select accrued from semester_accrual where semester_id=? and student_id=?",Integer.class,semester,capped)).isEqualTo(100);
        assertThat(jdbc.queryForObject("select accrued from semester_accrual where semester_id=? and student_id=?",Integer.class,semester,zero)).isZero();
        assertThat(ar(late)).isEqualTo(900);
        adjust(zero,-20);
        request(admin,"POST",SEMESTERS+"/"+semester+"/start",null,200);
        org.awaitility.Awaitility.await().during(java.time.Duration.ofSeconds(65)).atMost(java.time.Duration.ofSeconds(75)).untilAsserted(() -> {
            assertThat(ar(zero)).isEqualTo(980);
            assertThat(ar(late)).isEqualTo(900);
            assertThat(jdbc.queryForObject("select count(*) from ledger_entry where student_id=? and kind='SEMESTER_ACCRUAL'",Integer.class,capped)).isEqualTo(1);
        });
    }
    @Test void r6ScheduledStartFreezesZeroAccrualAtFirstProcessing() {
        long student=student("zeroNominal",faculty,group); adjust(student,-100);
        var planned=create(" scheduled", "2031-02-01T00:00:00Z"); long semester=planned.path("id").asLong();
        request(admin,"PUT",SETTINGS,settings(4,60,0,1000),200);
        clock.set(Instant.parse("2031-02-01T00:00:00Z"));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(90)).untilAsserted(() ->
            assertThat(jdbc.queryForObject("select count(*) from semester_accrual where semester_id=? and student_id=?",Integer.class,semester,student)).isEqualTo(1));
        assertThat(ar(student)).isEqualTo(900);
        request(admin,"PUT",SETTINGS,settings(4,60,100,1000),200);
        assertThat(items(get(SEMESTERS)).stream().filter(n->n.path("id").asLong()==semester).findFirst().orElseThrow().path("accrual").asInt()).isZero();
    }
    @Test void r10NewCheckpointsUseSavedPenaltyWhileExistingKeepSnapshot() {
        long teacher=teacher("snapshots","Snapshot teacher"); approve(teacher,department); var session=login("snapshots");
        request(admin,"PUT",SETTINGS,settings(4,60,200,1000),200);
        var before=request(session,"POST","/api/v1/checkpoints",checkpoint(group),201);
        request(admin,"PUT",SETTINGS,settings(7,120,200,1000),200);
        var after=request(session,"POST","/api/v1/checkpoints",checkpoint(group),201);
        assertThat(after.path("penaltyStep").asInt()).isEqualTo(7);
        assertThat(after.path("penaltyIntervalSeconds").asInt()).isEqualTo(120);
        var existing=get(session,"/api/v1/checkpoints/"+before.path("id").asLong());
        assertThat(existing.path("penaltyStep").asInt()).isEqualTo(4);
        assertThat(existing.path("penaltyIntervalSeconds").asInt()).isEqualTo(60);
    }
    private int ar(long student) { return get("/api/v1/users/"+student+"/profile").at("/balances/AR").asInt(); }
    private void adjust(long student,int amount) {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> ledger.post(List.of(
            new ru.campusrating.ledger.Posting(student,ru.campusrating.ledger.Currency.AR,ru.campusrating.ledger.EntryKind.PENALTY,amount,
                new ru.campusrating.ledger.Source(ru.campusrating.ledger.SourceType.CHECKPOINT,1L,"Fixture"),null,prefix+":"+student+":"+amount))));
    }
    @Test void r14StudentCannotReadOrMutateAdminResources() {
        student("student", faculty, group);
        var session = login("student");
        for (String path : List.of(SEMESTERS, SETTINGS, "/api/v1/admin/budgets", "/api/v1/admin/budgets/impact?limit=0"))
            problem(session,"GET",path,null,403,"FORBIDDEN");
        problem(session,"POST",SEMESTERS,Map.of("title", prefix,"startsAt","2032-01-01T00:00:00Z"),403,"FORBIDDEN");
        problem(session,"PUT",SETTINGS,settings(4,60,200,1000),403,"FORBIDDEN");
    }
}

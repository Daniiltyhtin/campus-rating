package ru.campusrating.identity;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.campusrating.ledger.Currency;
import ru.campusrating.ledger.EntryKind;
import ru.campusrating.ledger.LedgerApi;
import ru.campusrating.ledger.Posting;
import ru.campusrating.ledger.Source;
import ru.campusrating.ledger.SourceType;
import ru.campusrating.ledger.internal.SemesterAccrualJob;
import ru.campusrating.support.U13MailTestSupport;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import(MembershipEventProbe.class)
class MembershipApprovalIT extends U13MailTestSupport {
    private static final String PENDING = "/api/v1/memberships/pending";
    private static final String ME = "/api/v1/auth/me";
    @Autowired private MembershipEventProbe.Listener events;
    @Autowired private LedgerApi ledger;
    @Autowired private SemesterAccrualJob accrual;
    @Autowired private PlatformTransactionManager transactions;
    @LocalServerPort private int serverPort;
    private final List<Long> semesters = new ArrayList<>();

    @AfterEach
    void cleanupMembershipRecords() {
        String users = "select id from app_user where email like ?";
        if (exists("group_head"))
            jdbc.update("delete from group_head where student_id in (" + users + ") or group_id in (select id from study_group where code like ?)", prefix + "%", prefix + "%");
        jdbc.update("delete from submission where assignment_id in (select id from assignment where student_id in (" + users + "))", prefix + "%");
        jdbc.update("delete from assignment where student_id in (" + users + ") or checkpoint_id in (select id from checkpoint where author_id in (" + users + "))", prefix + "%", prefix + "%");
        jdbc.update("delete from activity_enrollment where student_id in (" + users + ")", prefix + "%");
        jdbc.update("delete from user_event where recipient_id in (" + users + ")", prefix + "%");
        jdbc.update("delete from semester_accrual where student_id in (" + users + ")", prefix + "%");
        for (long semester : semesters) {
            jdbc.update("delete from semester_accrual where semester_id=?", semester);
            jdbc.update("delete from semester where id=?", semester);
        }
    }

    @Test
    void p1RegistrationCreatesPendingMembershipWithInitialPostingAndNoEvent() {
        long id = student("orlov", faculty, group);
        var session = login("orlov");
        var me = get(session, ME);
        assertThat(me.path("membershipState").asText()).isEqualTo("PENDING");
        assertThat(me.path("groupHead").asBoolean(true)).isFalse();
        assertThat(me.at("/group/id").asLong()).isEqualTo(group);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where student_id=? and kind='INITIAL' and amount=1000", Integer.class, id)).isEqualTo(1);
        assertThat(ledger.balances(id).ar()).isEqualTo(1000);
        var tasks = get(session, "/api/v1/me/assignments?view=active");
        assertThat(tasks.path("membershipState").asText()).isEqualTo("PENDING");
        assertThat(tasks.path("items")).isEmpty();
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(eventsFor(id)).isEmpty());
    }

    @Test
    void ae11ApprovalAssignsOnlyCheckpointWhoseDeadlineIsAfterActivation() {
        long id = student("orlov", faculty, group);
        var teacher = approvedTeacher("teacher", department);
        long yesterday = request(teacher, "POST", "/api/v1/checkpoints", checkpoint("A", clock.instant().plus(Duration.ofDays(1))), 201).path("id").asLong();
        long tomorrow = request(teacher, "POST", "/api/v1/checkpoints", checkpoint("B", clock.instant().plus(Duration.ofDays(3))), 201).path("id").asLong();
        clock.advance(Duration.ofDays(2));
        Instant activation = clock.instant();
        request(teacher, "POST", approval(id), null, 204);
        var session = login("orlov");
        assertThat(get(session, ME).path("membershipState").asText()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select activated_at from group_membership where student_id=?", Timestamp.class, id).toInstant()).isEqualTo(activation);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(assignments(tomorrow, id)).isEqualTo(1));
        assertThat(assignments(yesterday, id)).isZero();
        assertThat(items(get(session, "/api/v1/me/assignments?view=active")).stream().map(n -> n.at("/checkpoint/id").asLong()).toList())
                .containsExactly(tomorrow);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(eventsFor(id)).containsExactly(new MembershipActivated(id, group, activation)));
    }

    @Test
    void p3RepeatedApprovalIsNoContentWithoutSecondEventOrAssignment() {
        long id = student("orlov", faculty, group);
        var teacher = approvedTeacher("teacher", department);
        long point = request(teacher, "POST", "/api/v1/checkpoints", checkpoint("B", clock.instant().plus(Duration.ofDays(3))), 201).path("id").asLong();
        request(teacher, "POST", approval(id), null, 204);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(assignments(point, id)).isEqualTo(1));
        clock.advance(Duration.ofMinutes(5));
        request(teacher, "POST", approval(id), null, 204);
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(eventsFor(id)).hasSize(1);
            assertThat(assignments(point, id)).isEqualTo(1);
        });
        assertThat(jdbc.queryForObject("select activated_at from group_membership where student_id=?", Timestamp.class, id).toInstant())
                .isEqualTo(clock.instant().minus(Duration.ofMinutes(5)));
    }

    @Test
    void p2TeacherDecidesOnlyForAccessibleGroupsAndUnapprovedTeacherSeesNothing() {
        long own = student("own", faculty, group);
        long foreign = student("foreign", otherFaculty, otherGroup);
        var teacher = approvedTeacher("teacher", department);
        problem(teacher, "POST", approval(foreign), null, 403, "FORBIDDEN");
        problem(teacher, "POST", rejection(foreign), null, 403, "FORBIDDEN");
        assertThat(pendingIds(teacher)).contains(own).doesNotContain(foreign);
        teacher("unapproved", "Unapproved");
        var unapproved = login("unapproved");
        problem(unapproved, "POST", approval(own), null, 403, "FORBIDDEN");
        problem(unapproved, "POST", rejection(own), null, 403, "FORBIDDEN");
        assertThat(items(get(unapproved, PENDING))).isEmpty();
        problem(admin, "POST", approval(own), null, 403, "FORBIDDEN");
        problem(browser(), "POST", approval(own), null, 401, "UNAUTHENTICATED");
        problem(browser(), "GET", PENDING, null, 401, "UNAUTHENTICATED");
        assertThat(state(own)).isEqualTo("PENDING");
        assertThat(state(foreign)).isEqualTo("PENDING");
        request(teacher, "POST", approval(own), null, 204);
        assertThat(state(own)).isEqualTo("ACTIVE");
    }

    @Test
    void p2GroupHeadDecidesOnlyInOwnGroupAndOrdinaryStudentIsForbidden() {
        long head = activeStudent("head", faculty, group);
        activeStudent("classmate", faculty, group);
        long own = student("own", faculty, group);
        long neighbour = student("neighbour", faculty, sameNameGroup);
        request(admin, "PUT", "/api/v1/admin/group-heads/" + group, Map.of("studentId", head), 200);
        var headSession = login("head");
        assertThat(get(headSession, ME).path("groupHead").asBoolean()).isTrue();
        assertThat(pendingIds(headSession)).containsExactly(own);
        problem(headSession, "POST", approval(neighbour), null, 403, "FORBIDDEN");
        problem(headSession, "POST", rejection(neighbour), null, 403, "FORBIDDEN");
        var ordinary = login("classmate");
        assertThat(get(ordinary, ME).path("groupHead").asBoolean(true)).isFalse();
        problem(ordinary, "POST", approval(own), null, 403, "FORBIDDEN");
        problem(ordinary, "POST", rejection(own), null, 403, "FORBIDDEN");
        assertThat(items(get(ordinary, PENDING))).isEmpty();
        request(headSession, "POST", approval(own), null, 204);
        assertThat(state(own)).isEqualTo("ACTIVE");
        assertThat(state(neighbour)).isEqualTo("PENDING");
    }

    @Test
    void p3p4RejectionKeepsGroupAccountPostingsAndSessionAndBlocksApproval() {
        long id = student("orlov", faculty, group);
        var session = login("orlov");
        var teacher = approvedTeacher("teacher", department);
        request(teacher, "POST", rejection(id), null, 204);
        var me = get(session, ME);
        assertThat(me.path("membershipState").asText()).isEqualTo("REJECTED");
        assertThat(me.at("/group/id").asLong()).isEqualTo(group);
        assertThat(ledger.balances(id).ar()).isEqualTo(1000);
        request(teacher, "POST", rejection(id), null, 204);
        problem(teacher, "POST", approval(id), null, 409, "INVALID_STATE");
        assertThat(state(id)).isEqualTo("REJECTED");
        assertThat(pendingIds(teacher)).doesNotContain(id);
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(eventsFor(id)).isEmpty());
        long active = student("active", faculty, group);
        request(teacher, "POST", approval(active), null, 204);
        problem(teacher, "POST", rejection(active), null, 409, "INVALID_STATE");
        assertThat(state(active)).isEqualTo("ACTIVE");
    }

    @Test
    void p3p4UnknownStudentOrUserWithoutMembershipIsNotFound() {
        var teacher = approvedTeacher("teacher", department);
        long other = teacher("other", "Other teacher");
        problem(teacher, "POST", approval(ABSENT), null, 404, "NOT_FOUND");
        problem(teacher, "POST", rejection(ABSENT), null, 404, "NOT_FOUND");
        problem(teacher, "POST", approval(other), null, 404, "NOT_FOUND");
        problem(teacher, "POST", rejection(other), null, 404, "NOT_FOUND");
    }

    @Test
    void p5RejectedStudentRequestsAnotherGroupAndMovesBetweenTeacherLists() {
        long id = student("orlov", faculty, group);
        var session = login("orlov");
        var first = approvedTeacher("first", department);
        var second = approvedTeacher("second", otherDepartment);
        request(first, "POST", rejection(id), null, 204);
        var mismatch = problem(session, "PUT", "/api/v1/me/membership", Map.of("facultyId", faculty, "groupId", otherGroup), 400, "VALIDATION_FAILED");
        assertThat(fields(mismatch)).contains("groupId");
        var missing = problem(session, "PUT", "/api/v1/me/membership", Map.of("facultyId", faculty, "groupId", ABSENT), 400, "VALIDATION_FAILED");
        assertThat(fields(missing)).contains("groupId");
        assertThat(state(id)).isEqualTo("REJECTED");
        clock.advance(Duration.ofHours(1));
        var moved = request(session, "PUT", "/api/v1/me/membership", Map.of("facultyId", otherFaculty, "groupId", otherGroup), 200);
        assertThat(moved.path("role").asText()).isEqualTo("STUDENT");
        assertThat(moved.path("id").asLong()).isEqualTo(id);
        assertThat(moved.path("membershipState").asText()).isEqualTo("PENDING");
        assertThat(moved.at("/group/id").asLong()).isEqualTo(otherGroup);
        assertThat(moved.path("groupHead").asBoolean(true)).isFalse();
        assertThat(pendingIds(first)).doesNotContain(id);
        var row = items(get(second, PENDING)).stream().filter(n -> n.path("studentId").asLong() == id).findFirst().orElseThrow();
        assertThat(row.at("/group/id").asLong()).isEqualTo(otherGroup);
        assertThat(Instant.parse(row.path("requestedAt").asText())).isEqualTo(clock.instant());
        problem(session, "PUT", "/api/v1/me/membership", Map.of("facultyId", faculty, "groupId", group), 409, "INVALID_STATE");
        request(second, "POST", approval(id), null, 204);
        problem(session, "PUT", "/api/v1/me/membership", Map.of("facultyId", faculty, "groupId", group), 409, "INVALID_STATE");
        assertThat(get(session, ME).at("/group/id").asLong()).isEqualTo(otherGroup);
    }

    @Test
    void p5RejectedStudentMayRequestTheSameGroupAgainAndOthersCannotUseOwnMembership() {
        long id = student("orlov", faculty, group);
        var session = login("orlov");
        var teacher = approvedTeacher("teacher", department);
        request(teacher, "POST", rejection(id), null, 204);
        var again = request(session, "PUT", "/api/v1/me/membership", Map.of("facultyId", faculty, "groupId", group), 200);
        assertThat(again.path("membershipState").asText()).isEqualTo("PENDING");
        assertThat(pendingIds(teacher)).contains(id);
        problem(teacher, "PUT", "/api/v1/me/membership", Map.of("facultyId", faculty, "groupId", group), 403, "FORBIDDEN");
        problem(admin, "PUT", "/api/v1/me/membership", Map.of("facultyId", faculty, "groupId", group), 403, "FORBIDDEN");
        problem(browser(), "PUT", "/api/v1/me/membership", Map.of("facultyId", faculty, "groupId", group), 401, "UNAUTHENTICATED");
    }

    @Test
    void ktd4ConcurrentApprovalAndRejectionHaveExactlyOneOutcome() throws Exception {
        approvedTeacher("teacher", department);
        long head = activeStudent("head", faculty, group);
        request(admin, "PUT", "/api/v1/admin/group-heads/" + group, Map.of("studentId", head), 200);
        var teacher = new Raw(email("teacher"));
        var headSession = new Raw(email("head"));
        var students = new ArrayList<Long>();
        for (int i = 0; i < 8; i++) students.add(student("race" + i, faculty, group));
        var approved = new ArrayList<Long>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < students.size(); i++) {
                long id = students.get(i);
                var barrier = new CyclicBarrier(2);
                var approver = i % 2 == 0 ? teacher : headSession;
                var rejecter = i % 2 == 0 ? headSession : teacher;
                var approve = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return approver.post(approval(id)); });
                var reject = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return rejecter.post(rejection(id)); });
                int approveStatus = approve.get(30, TimeUnit.SECONDS);
                int rejectStatus = reject.get(30, TimeUnit.SECONDS);
                assertThat(List.of(approveStatus, rejectStatus)).containsExactlyInAnyOrder(204, 409);
                assertThat(state(id)).isEqualTo(approveStatus == 204 ? "ACTIVE" : "REJECTED");
                if (approveStatus == 204) approved.add(id);
            }
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            for (long id : students) assertThat(eventsFor(id)).hasSize(approved.contains(id) ? 1 : 0);
        });
    }

    @Test
    void p6PendingAndRejectedStudentsAreNotEligibleForActivityButReceiveSemesterAccrual() {
        long active = activeStudent("active", faculty, group);
        long pending = student("pending", faculty, group);
        long rejected = student("rejected", faculty, group);
        var teacher = approvedTeacher("teacher", department);
        request(teacher, "POST", rejection(rejected), null, 204);
        var activity = request(teacher, "POST", "/api/v1/activities", Map.of("title", prefix + " activity", "description", "", "type", "ACADEMIC",
                "startsAt", clock.instant().plus(Duration.ofHours(1)).toString(), "groupIds", List.of(group), "maxPoints", 100), 201).path("id").asLong();
        clock.advance(Duration.ofHours(1));
        var body = Map.of("items", List.of(Map.of("studentId", pending, "level", 50), Map.of("studentId", rejected, "level", 50)));
        var failure = problem(teacher, "POST", "/api/v1/activities/" + activity + "/participation", body, 422, "STUDENT_NOT_ELIGIBLE");
        assertThat(failure.path("studentIds").valueStream().map(JsonNode::asLong).toList()).containsExactlyInAnyOrder(pending, rejected);
        assertThat(jdbc.queryForObject("select count(*) from activity_award where activity_id=?", Integer.class, activity)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from activity_budget where teacher_id in (select id from app_user where email=?)", Integer.class, email("teacher"))).isZero();
        var participants = items(get(teacher, "/api/v1/activities/" + activity + "/participants")).stream().map(n -> n.at("/student/id").asLong()).toList();
        assertThat(participants).contains(active).doesNotContain(pending, rejected);
        for (long id : List.of(pending, rejected)) penalty(id, -100);
        clock.advance(Duration.ofMinutes(1));
        long semester = jdbc.queryForObject("insert into semester(title,starts_at) values (?,?) returning id", Long.class, prefix + " semester", Timestamp.from(clock.instant()));
        semesters.add(semester);
        accrual.runOnce();
        for (long id : List.of(pending, rejected)) {
            assertThat(jdbc.queryForObject("select count(*) from ledger_entry where student_id=? and kind='SEMESTER_ACCRUAL'", Integer.class, id)).isEqualTo(1);
            assertThat(ledger.balances(id).ar()).isEqualTo(1000);
        }
    }

    @Test
    void p1ImportedStudentIsActiveImmediatelyWithEventAndNotPending() {
        long id = imported("imported");
        var teacher = approvedTeacher("teacher", department);
        var row = items(get(teacher, "/api/v1/groups/" + group + "/students")).stream().filter(n -> n.path("id").asLong() == id).findFirst().orElseThrow();
        assertThat(row.path("membershipState").asText()).isEqualTo("ACTIVE");
        assertThat(pendingIds(teacher)).doesNotContain(id);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(eventsFor(id)).hasSize(1));
    }

    @Test
    void p9PendingListHasOnlyPendingOfDecidableGroupsOrderedByRequestTimeThenId() {
        clock.advance(Duration.ofMinutes(1));
        long late = student("late", faculty, group);
        clock.set(clock.instant().minus(Duration.ofMinutes(1)));
        long earlyA = student("earlya", faculty, sameNameGroup);
        long earlyB = student("earlyb", faculty, group);
        long rejected = student("rejected", faculty, group);
        long active = student("active", faculty, group);
        long foreign = student("foreign", otherFaculty, otherGroup);
        var teacher = approvedTeacher("teacher", department);
        request(teacher, "POST", rejection(rejected), null, 204);
        request(teacher, "POST", approval(active), null, 204);
        var rows = items(get(teacher, PENDING)).stream().filter(n -> List.of(late, earlyA, earlyB, rejected, active, foreign).contains(n.path("studentId").asLong())).toList();
        assertThat(rows.stream().map(n -> n.path("studentId").asLong()).toList()).containsExactly(earlyA, earlyB, late);
        var first = rows.getFirst();
        assertThat(first.path("fullName").asText()).isEqualTo("earlya");
        assertThat(first.path("email").asText()).isEqualTo(email("earlya"));
        assertThat(first.at("/group/id").asLong()).isEqualTo(sameNameGroup);
        assertThat(first.at("/group/faculty/id").asLong()).isEqualTo(faculty);
        assertThat(Instant.parse(first.path("requestedAt").asText())).isEqualTo(clock.instant());
        assertThat(Instant.parse(rows.getLast().path("requestedAt").asText())).isEqualTo(clock.instant().plus(Duration.ofMinutes(1)));
        var all = items(get(teacher, PENDING));
        assertThat(all).isSortedAccordingTo(java.util.Comparator.comparing((JsonNode n) -> Instant.parse(n.path("requestedAt").asText()))
                .thenComparingLong(n -> n.path("studentId").asLong()));
        assertThat(all.stream().map(n -> n.at("/group/id").asLong()).distinct().toList()).doesNotContain(otherGroup, olderGroup, thirdGroup);
    }

    private boolean exists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, "public." + table));
    }

    private String approval(long student) { return "/api/v1/memberships/" + student + "/approval"; }
    private String rejection(long student) { return "/api/v1/memberships/" + student + "/rejection"; }

    private Browser approvedTeacher(String suffix, long targetDepartment) {
        long id = teacher(suffix, "Teacher " + suffix);
        approve(id, targetDepartment);
        return login(suffix);
    }

    private long activeStudent(String suffix, long facultyId, long groupId) {
        long id = student(suffix, facultyId, groupId);
        jdbc.update("update group_membership set state='ACTIVE',activated_at=? where student_id=?", Timestamp.from(clock.instant()), id);
        return id;
    }

    private String state(long student) {
        return jdbc.queryForObject("select state from group_membership where student_id=?", String.class, student);
    }

    private List<Long> pendingIds(Browser session) {
        return items(get(session, PENDING)).stream().map(n -> n.path("studentId").asLong()).toList();
    }

    private List<String> fields(JsonNode problem) {
        return problem.path("fieldErrors").valueStream().map(n -> n.path("field").asText()).toList();
    }

    private List<MembershipActivated> eventsFor(long student) {
        return events.committed().stream().filter(e -> e.studentId() == student).toList();
    }

    private int assignments(long checkpoint, long student) {
        return jdbc.queryForObject("select count(*) from assignment where checkpoint_id=? and student_id=?", Integer.class, checkpoint, student);
    }

    private Map<String, Object> checkpoint(String title, Instant deadline) {
        return Map.of("title", prefix + " " + title, "description", "", "groupIds", List.of(group), "deadline", deadline.toString(), "cost", 60);
    }

    private void penalty(long student, int amount) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> ledger.post(List.of(new Posting(student, Currency.AR, EntryKind.PENALTY, amount,
                new Source(SourceType.CHECKPOINT, 1L, "Fixture"), null, prefix + ":penalty:" + student))));
    }

    private final class Raw {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final RestTestClient client = RestTestClient.bindToServer(new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().cookieHandler(cookies).build())).baseUrl("http://127.0.0.1:" + serverPort).build();

        Raw(String email) {
            assertThat(send("/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).isEqualTo(200);
        }

        int post(String path) { return send(path, null); }

        private int send(String path, String body) {
            client.get().uri("/api/v1/auth/csrf").exchange().expectStatus().isNoContent();
            String token = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN"))
                    .map(c -> URLDecoder.decode(c.getValue(), StandardCharsets.UTF_8)).findFirst().orElseThrow();
            var call = client.post().uri(path).contentType(MediaType.APPLICATION_JSON).header("X-XSRF-TOKEN", token);
            if (body != null) call.body(body);
            return call.exchange().returnResult(String.class).getStatus().value();
        }
    }
}

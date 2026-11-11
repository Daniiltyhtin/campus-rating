package ru.campusrating.activity;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivityIT extends ActivityTestSupport {
    @Test
    void creationValidatesFieldsScopeAndApproval() throws Exception {
        var valid = create("ACADEMIC", START, group, 75, teacherSession, 201);
        assertThat(valid.path("currency").asText()).isEqualTo("BUA");
        assertThat(valid.path("author").path("id").asLong()).isEqualTo(teacher);
        assertThat(valid.path("groups").get(0).path("faculty").path("name").asText()).isEqualTo("Факультет");
        assertThat(valid.path("enrolled").isNull()).isTrue();
        assertThat(valid.path("myAward").isNull()).isTrue();
        assertThat(valid.path("enrolledCount").asInt()).isZero();
        assertThat(valid.path("confirmedCount").asInt()).isZero();
        assertThat(create("SOCIAL", START, group, 1, teacherSession, 400).path("fieldErrors").get(0).path("field").asText()).isEqualTo("maxPoints");
        assertThat(create("SOCIAL", NOW.minusSeconds(1), group, 50, teacherSession, 400).path("fieldErrors").get(0).path("field").asText()).isEqualTo("startsAt");
        create("SOCIAL", NOW, group, 50, teacherSession, 400);
        problem(create("SOCIAL", START, otherGroup, 50, teacherSession, 403), "GROUP_NOT_ACCESSIBLE");
        create("SOCIAL", START, group, 50, unapprovedSession, 403);
        create("SOCIAL", START, group, 50, studentSession, 403);
    }

    @Test
    void listFiltersAndOrdersAndReadsPersonalEnrollmentAndAward() throws Exception {
        long near = activity("SOCIAL", START);
        long far = activity("ACADEMIC", START.plusSeconds(3600));
        long recent = activity("SOCIAL", START.plusSeconds(7200));
        long old = activity("SOCIAL", START.plusSeconds(10800));
        update("update activity set starts_at=? where id=?", NOW.minusSeconds(1), recent);
        update("update activity set starts_at=? where id=?", NOW.minusSeconds(3600), old);
        create("SOCIAL", START, otherGroup, 50, grantOtherGroup(), 201);
        request(put(path(near) + "/enrollment"), studentSession, 200);
        long entry = id("""
                insert into ledger_entry(student_id,currency,kind,amount,source_type,source_id,source_title,author_id,
                author_name,idempotency_key,created_at) values (?,'BOA','ACTIVITY_AWARD',38,'ACTIVITY',?,'Activity',?,
                'Преподаватель',?,?) returning id
                """, student, recent, teacher, prefix, NOW);
        update("""
                insert into activity_award(activity_id,student_id,group_id,level,amount,currency,author_id,confirmed_at,ledger_entry_id)
                values (?,?,?,50,38,'BOA',?,?,?)
                """, recent, student, group, teacher, NOW, entry);
        var items = request(get("/api/v1/activities"), studentSession, 200).path("items");
        assertThat(items).hasSize(4);
        assertThat(items.get(0).path("id").asLong()).isEqualTo(near);
        assertThat(items.get(1).path("id").asLong()).isEqualTo(far);
        assertThat(items.get(2).path("id").asLong()).isEqualTo(recent);
        assertThat(items.get(3).path("id").asLong()).isEqualTo(old);
        assertThat(items.get(0).path("enrolled").asBoolean()).isTrue();
        assertThat(items.get(1).path("enrolled").isNull()).isTrue();
        assertThat(items.get(2).path("myAward").path("amount").asInt()).isEqualTo(38);
        assertThat(items.get(2).path("myAward").path("level").asInt()).isEqualTo(50);
        assertThat(items.get(2).path("confirmedCount").asInt()).isEqualTo(1);
        var pendingItems = request(get("/api/v1/activities"), pendingSession, 200).path("items");
        assertThat(pendingItems).hasSize(4);
        assertThat(pendingItems.get(0).path("enrolled").asBoolean()).isFalse();
        assertThat(pendingItems.get(2).path("myAward").isNull()).isTrue();
    }

    @Test
    void authorRetainsReadAccessAfterScopeLossAndOthersAreForbidden() throws Exception {
        long activity = activity("SOCIAL", START);
        update("delete from teacher_approval where teacher_id=?", teacher);
        assertThat(request(get("/api/v1/activities"), teacherSession, 200).path("items")).hasSize(1);
        request(get(path(activity)), teacherSession, 200);
        assertThat(request(get("/api/v1/activities"), otherTeacherSession, 200).path("items")).isEmpty();
        problem(request(get(path(activity)), otherTeacherSession, 403), "FORBIDDEN");
        problem(request(get(path(activity)), outsiderSession, 403), "FORBIDDEN");
        request(get(path(activity)), pendingSession, 200);
        problem(request(get(path(Long.MAX_VALUE)), studentSession, 404), "NOT_FOUND");
        request(get(path(activity)), null, 401);
    }

    @Test
    void enrollmentIsIdempotentAndChecksTypeMembershipAndGroup() throws Exception {
        long social = activity("SOCIAL", START);
        var first = request(put(path(social) + "/enrollment"), studentSession, 200);
        assertThat(first.path("enrolled").asBoolean()).isTrue();
        assertThat(first.path("enrollmentOpen").asBoolean()).isTrue();
        assertThat(first.path("confirmationOpen").asBoolean()).isFalse();
        var enrolledAt = jdbc.queryForObject("select enrolled_at from activity_enrollment where activity_id=? and student_id=?", Timestamp.class, social, student);
        clock.set(NOW.plusSeconds(1));
        assertThat(request(put(path(social) + "/enrollment"), studentSession, 200).path("enrolledCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select enrolled_at from activity_enrollment where activity_id=? and student_id=?", Timestamp.class, social, student)).isEqualTo(enrolledAt);
        problem(request(put(path(social) + "/enrollment"), pendingSession, 422), "MEMBERSHIP_NOT_ACTIVE");
        problem(request(put(path(social) + "/enrollment"), outsiderSession, 403), "FORBIDDEN");
        long academic = activity("ACADEMIC", START);
        problem(request(put(path(academic) + "/enrollment"), studentSession, 409), "INVALID_STATE");
        problem(request(delete(path(academic) + "/enrollment"), studentSession, 409), "INVALID_STATE");
    }

    @Test
    void cancellationIsIdempotentAndDoesNotRequireActiveMembership() throws Exception {
        long social = activity("SOCIAL", START);
        request(put(path(social) + "/enrollment"), studentSession, 200);
        update("update group_membership set state='PENDING',activated_at=null where student_id=?", student);
        var result = request(delete(path(social) + "/enrollment"), studentSession, 200);
        assertThat(result.path("enrolled").asBoolean()).isFalse();
        assertThat(result.path("enrolledCount").asInt()).isZero();
        request(delete(path(social) + "/enrollment"), studentSession, 200);
        problem(request(delete(path(social) + "/enrollment"), outsiderSession, 403), "FORBIDDEN");
    }

    @Test
    void exactStartAndAfterRejectEnrollmentAndCancellation() throws Exception {
        long social = activity("SOCIAL", START);
        request(put(path(social) + "/enrollment"), studentSession, 200);
        for (var instant : new Instant[]{START, START.plusSeconds(1)}) {
            clock.set(instant);
            problem(request(put(path(social) + "/enrollment"), studentSession, 422), "ENROLLMENT_CLOSED");
            problem(request(delete(path(social) + "/enrollment"), studentSession, 422), "ENROLLMENT_CLOSED");
            var view = request(get(path(social)), studentSession, 200);
            assertThat(view.path("enrollmentOpen").asBoolean()).isFalse();
            assertThat(view.path("confirmationOpen").asBoolean()).isTrue();
            assertThat(view.path("enrolledCount").asInt()).isEqualTo(1);
        }
    }

    @Test
    void schemaRejectsInvalidTypesLevelsCurrencyPointsAndSpent() throws Exception {
        long activity = activity("SOCIAL", START);
        assertThatThrownBy(() -> update("update activity set type='OTHER' where id=?", activity))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (int maximum : new int[]{1, 10001}) {
            assertThatThrownBy(() -> update("update activity set max_points=? where id=?", maximum, activity))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> update("insert into activity_budget(teacher_id,group_id,month,spent) values (?,?,'2026-11-01',-1)", teacher, group))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (Object[] invalid : new Object[][]{{30, "BOA"}, {50, "AR"}}) {
            assertThatThrownBy(() -> update("""
                    insert into activity_award(activity_id,student_id,group_id,level,amount,currency,author_id,confirmed_at)
                    values (?,?,?,?,38,?,?,?)
                    """, activity, student, group, invalid[0], invalid[1], teacher, NOW))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> update("insert into activity_group(activity_id,group_id) values (?,?)", activity, Long.MAX_VALUE))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

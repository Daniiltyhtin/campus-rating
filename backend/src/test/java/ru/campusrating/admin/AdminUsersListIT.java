package ru.campusrating.admin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.campusrating.support.U13HttpTestSupport;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

class AdminUsersListIT extends U13HttpTestSupport {
    private static final String USERS = "/api/v1/admin/users";
    private String query(String parameters) { return USERS + "?search=" + prefix + parameters; }
    private JsonNode row(long id) {
        return items(get(query(""))).stream().filter(item -> item.path("id").asLong() == id).findFirst().orElseThrow();
    }
    private long namedStudent(String suffix, String name) {
        long id = student(suffix, faculty, group);
        jdbc.update("update app_user set full_name=? where id=?", name, id);
        return id;
    }

    @Test void ae2RowsContainMembershipApprovalAffiliationAndTimes() {
        clock.set(Instant.parse("2026-11-12T15:00:00Z"));
        long smirnova = namedStudent("smirnova", "Смирнова Анна Олеговна");
        long orlov = namedStudent("orlov", "Орлов");
        jdbc.update("update group_membership set state='ACTIVE',activated_at=? where student_id=?", java.sql.Timestamp.from(clock.instant()), smirnova);
        long petrov = teacher("petrov", "Петров Павел Петрович"); approve(petrov, department);
        long volkov = teacher("volkov", "Волков Максим Игоревич");
        assertThat(row(smirnova).path("status").asText()).isEqualTo("MEMBERSHIP_ACTIVE");
        assertThat(row(orlov).path("status").asText()).isEqualTo("MEMBERSHIP_PENDING");
        for (long id : List.of(smirnova, orlov)) {
            assertThat(row(id).path("role").asText()).isEqualTo("STUDENT");
            assertThat(row(id).at("/group/id").asLong()).isEqualTo(group);
        }
        assertThat(row(petrov).path("status").asText()).isEqualTo("APPROVED");
        assertThat(row(volkov).path("status").asText()).isEqualTo("PENDING");
        for (long id : List.of(petrov, volkov)) {
            assertThat(row(id).path("role").asText()).isEqualTo("TEACHER");
            assertThat(row(id).at("/department/id").asLong()).isEqualTo(department);
            assertThat(row(id).at("/department/faculty/id").asLong()).isEqualTo(faculty);
        }
        for (long id : List.of(smirnova, orlov, petrov, volkov)) {
            assertThat(Instant.parse(row(id).path("createdAt").asText())).isEqualTo(clock.instant());
            assertThat(row(id).path("lastLoginAt").isNull()).isTrue();
            assertThat(row(id).path("passwordSetAt").isNull()).isTrue();
        }
        var administrator = row(get(admin, "/api/v1/auth/me").path("id").asLong());
        assertThat(administrator.path("role").asText()).isEqualTo("ADMIN");
        for (String field : List.of("group", "department", "status")) assertThat(administrator.path(field).isNull()).isTrue();
        assertThat(administrator.hasNonNull("createdAt")).isTrue();
        assertThat(administrator.hasNonNull("lastLoginAt")).isTrue();
        clock.set(Instant.parse("2026-11-13T07:00:00Z"));
        request(admin, "POST", "/api/v1/users/" + smirnova + "/temporary-password", null, 200);
        assertThat(Instant.parse(row(smirnova).path("passwordSetAt").asText())).isEqualTo(clock.instant());
    }

    @Test void ae3SearchAndRoleAndStatusAreCombined() {
        long petrov = teacher("petrov", "Петров Павел Петрович"); approve(petrov, department);
        long smirnova = namedStudent("smirnova", "Смирнова Анна Олеговна");
        jdbc.update("update group_membership set state='ACTIVE',activated_at=? where student_id=?", java.sql.Timestamp.from(clock.instant()), smirnova);
        long orlov = namedStudent("orlov", "Орлов");
        teacher("volkov", "Волков Максим Игоревич");
        assertThat(ids(get(USERS + "?search=PETROV@"))).containsExactly(petrov);
        assertThat(ids(get(USERS + "?search=смир"))).containsExactly(smirnova);
        assertThat(items(get(query("&role=STUDENT&status=PENDING")))).isEmpty();
        assertThat(ids(get(query("&status=MEMBERSHIP_PENDING")))).containsExactly(orlov);
        assertThat(items(get(USERS + "?search=смир&role=TEACHER"))).isEmpty();
        assertThat(get(USERS + "?search=с")).isEqualTo(get(USERS));
        assertThat(ids(get(USERS + "?search=  " + email("PETROV") + "  &role=TEACHER&status=APPROVED"))).containsExactly(petrov);
    }

    @Test void ae5SortsNullLoginsFirstThenOldestAndNewCreationFirst() {
        clock.set(Instant.parse("2026-11-12T15:00:00Z"));
        long smirnova = namedStudent("smirnova", "Смирнова Анна Олеговна");
        long orlov = namedStudent("orlov", "Орлов");
        long petrov = teacher("petrov", "Петров Павел Петрович");
        long volkov = teacher("volkov", "Волков Максим Игоревич");
        clock.set(Instant.parse("2026-11-13T06:00:00Z")); login("petrov");
        clock.set(Instant.parse("2026-11-13T07:15:00Z")); login("smirnova");
        clock.set(Instant.parse("2026-11-13T08:00:00Z")); login("petrov");
        clock.set(Instant.parse("2026-11-13T08:30:00Z")); long newest = namedStudent("new", "Тест Новый");
        clock.set(Instant.parse("2026-11-13T09:00:00Z"));
        request(browser(), "POST", "/api/v1/auth/login", java.util.Map.of("email", email("volkov"), "password", "wrong"), 401);
        var own = List.of(smirnova, orlov, petrov, volkov, newest);
        assertThat(ids(get(query("&sort=lastLogin"))).stream().filter(own::contains).toList()).containsExactly(volkov, orlov, newest, smirnova, petrov);
        assertThat(ids(get(query("&sort=created"))).stream().filter(own::contains).toList()).containsExactly(newest, volkov, orlov, petrov, smirnova);
        assertThat(row(volkov).path("lastLoginAt").isNull()).isTrue();
    }

    @ParameterizedTest @CsvSource({"name,false", "created,false", "lastLogin,false", "name,true", "created,true", "lastLogin,true"})
    void ae6KeysetPagesFiftyFiftyTwentyIncludingEqualNameAndTime(String sort, boolean equalNames) {
        var expected = new ArrayList<Long>();
        for (int i = 1; i <= 120; i++) {
            long id = jdbc.queryForObject("insert into app_user(email,role,full_name) values (?,'STUDENT',?) returning id", Long.class,
                    email("page" + i), "Тест " + String.format("%03d", i));
            jdbc.update("insert into group_membership(student_id,group_id,state) values (?,?,'PENDING')", id, group);
            expected.add(id);
        }
        jdbc.update("update app_user set created_at=? where email like ?", java.sql.Timestamp.from(Instant.parse("2026-11-12T15:00:00Z")), prefix + "page%");
        if (equalNames) jdbc.update("update app_user set full_name='Тест 050' where email like ?", prefix + "page%");
        var all = new ArrayList<Long>();
        String cursor = null;
        for (int size : List.of(50, 50, 20)) {
            var page = get(query("page&role=STUDENT&status=MEMBERSHIP_PENDING&sort=" + sort + (cursor == null ? "" : "&cursor=" + cursor)));
            assertThat(items(page)).hasSize(size);
            all.addAll(ids(page));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
            if (size == 50) assertThat(cursor).isNotBlank();
        }
        assertThat(cursor).isNull();
        assertThat(all).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        String firstCursor = get(query("page&role=STUDENT&status=MEMBERSHIP_PENDING&sort=" + sort)).path("nextCursor").asText();
        String parameters = "?search=" + prefix + "page&role=STUDENT&status=MEMBERSHIP_PENDING&sort=" + sort;
        for (String changed : List.of(parameters.replace(prefix + "page", prefix), parameters.replace("role=STUDENT", "role=TEACHER"),
                parameters.replace("status=MEMBERSHIP_PENDING", "status=MEMBERSHIP_ACTIVE"),
                parameters.replace("sort=" + sort, "sort=" + (sort.equals("name") ? "created" : "name")))) {
            problem(admin, "GET", USERS + changed + "&cursor=" + firstCursor, null, 400, "VALIDATION_FAILED");
        }
    }

    @Test void nonAdministratorsCannotReadUserList() {
        student("student", faculty, group); teacher("teacher", "Петров Павел Петрович");
        for (var session : List.of(login("student"), login("teacher"))) problem(session, "GET", USERS + "?status=PENDING&sort=created", null, 403, "FORBIDDEN");
    }
}

package ru.campusrating.directory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.TestPropertySource;
import ru.campusrating.support.PostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "spring.liquibase.contexts=default,demo")
class DirectoryApiIT extends PostgresIntegrationTest {
    @Autowired private DirectoryApi directory;
    @Autowired private JdbcTemplate jdbc;
    @LocalServerPort private int port;

    @Test
    void facultiesAndDepartmentsMatchContract() throws Exception {
        assertThat(get("/faculties").path("items").get(0).path("code").asText()).isEqualTo("ФИТ");
        var departments = get("/faculties/1/departments").path("items");
        assertThat(departments.size()).isEqualTo(1);
        assertThat(departments.get(0).path("id").asLong()).isEqualTo(101);
        assertThat(departments.get(0).path("code").asText()).isEqualTo("ПИ");
        assertThat(get("/faculties/2/departments").path("items").isEmpty()).isTrue();
    }

    @Test
    void listsOnlyGroupsOfRequestedFacultyInNameOrder() throws Exception {
        var otherGroupId = jdbc.queryForObject("""
                insert into study_group (code, name, faculty_id, admission_year)
                values ('OTHER', 'Другая группа', 2, 2026) returning id
                """, Long.class);
        try {
            assertThat(get("/faculties/2/groups").path("items").get(0).path("id").asLong()).isEqualTo(otherGroupId);
            assertThat(get("/faculties/1/groups").path("items").size()).isEqualTo(2);
        } finally {
            jdbc.update("delete from study_group where id = ?", otherGroupId);
        }
        var groups = get("/faculties/1/groups").path("items");
        assertThat(groups.size()).isEqualTo(2);
        assertThat(groups.get(0).path("name").asText()).isEqualTo("ИВТ-21");
        assertThat(groups.get(1).path("name").asText()).isEqualTo("ИВТ-22");
        for (var group : groups) {
            assertThat(group.path("faculty").path("id").asLong()).isEqualTo(1);
            assertThat(group.has("admissionYear")).isFalse();
        }
        assertThat(get("/faculties/2/groups").path("items").isEmpty()).isTrue();
    }

    @Test
    void unknownFacultyIsNotFoundForBothLists() throws Exception {
        for (var suffix : List.of("groups", "departments")) {
            var response = request("/faculties/999999/" + suffix);
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.headers().firstValue("content-type").orElseThrow())
                    .startsWith("application/problem+json");
            assertThat(JsonMapper.builder().build().readTree(response.body()).path("code").asText())
                    .isEqualTo("NOT_FOUND");
        }
    }

    @Test
    void seamReturnsFacultyAndAdmissionYearAndOmitsMissingIds() {
        var group = directory.group(11).orElseThrow();
        assertThat(group.faculty().id()).isEqualTo(1);
        assertThat(group.admissionYear()).isEqualTo(2025);
        assertThat(directory.group(999999)).isEmpty();
        assertThat(directory.groups(List.of())).isEmpty();
        assertThat(directory.groups(List.of(11L, 12L, 999999L))).containsOnlyKeys(11L, 12L);
        assertThat(directory.groupsOfFaculty(1)).extracting(GroupInfo::id).containsExactly(11L, 12L);
        assertThat(directory.allGroups()).extracting(GroupInfo::id).containsExactly(11L, 12L);
        assertThat(directory.department(101).orElseThrow().faculty().code()).isEqualTo("ФИТ");
        assertThat(directory.department(999999)).isEmpty();
    }

    @Test
    void repeatedDemoSeedDoesNotDuplicateRowsOrReuseSeedIds() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            var seed = new ClassPathResource("db/changelog/demo/202611090900-demo-directory.sql");
            ScriptUtils.executeSqlScript(connection, seed);
            ScriptUtils.executeSqlScript(connection, seed);
        }
        assertThat(jdbc.queryForObject("select count(*) from faculty", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from study_group", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from department", Integer.class)).isEqualTo(1);
        var generated = jdbc.queryForObject("insert into faculty (code, name) values ('TEST', 'Test') returning id", Long.class);
        try {
            assertThat(generated).isGreaterThan(2);
        } finally {
            jdbc.update("delete from faculty where id = ?", generated);
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void minimalIdentityTablesSupportActiveAndPendingMemberships() {
        var activeId = jdbc.queryForObject("""
                insert into app_user (email, role, full_name)
                values ('active-u4@example.test', 'STUDENT', 'Активный студент') returning id
                """, Long.class);
        var pendingId = jdbc.queryForObject("""
                insert into app_user (email, role, full_name)
                values ('pending-u4@example.test', 'STUDENT', 'Ожидающий студент') returning id
                """, Long.class);
        jdbc.update("""
                insert into group_membership (student_id, group_id, state, activated_at)
                values (?, 11, 'ACTIVE', timestamptz '2026-11-01 00:00:00+00'), (?, 11, 'PENDING', null)
                """, activeId, pendingId);
        assertThat(jdbc.queryForList("""
                select student_id from group_membership where group_id = 11 and state = 'ACTIVE'
                """, Long.class)).contains(activeId).doesNotContain(pendingId);
        assertThat(jdbc.queryForObject("""
                select activated_at is null from group_membership where student_id = ?
                """, Boolean.class, pendingId)).isTrue();
    }

    private JsonNode get(String path) throws Exception {
        var response = request(path);
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonMapper.builder().build().readTree(response.body());
    }

    private HttpResponse<String> request(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/api/v1/directory" + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}

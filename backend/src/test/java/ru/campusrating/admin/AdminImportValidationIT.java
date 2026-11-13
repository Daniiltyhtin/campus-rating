package ru.campusrating.admin;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import ru.campusrating.support.U13HttpTestSupport;
import static org.assertj.core.api.Assertions.assertThat;

class AdminImportValidationIT extends U13HttpTestSupport {
    @Test void semanticErrorInEarlierColumnPrecedesEmptyLaterColumn() {
        var csv="email,last_name,first_name,middle_name,faculty_code,group_code\nbad-email,,,,missing,missing\n";
        var result=adminWire().upload("/api/v1/admin/import/STUDENTS","students.csv",csv.getBytes(StandardCharsets.UTF_8),200);
        assertThat(result.at("/rejected/0/reason").asText()).isEqualTo("неверный email");
    }

    private static final String HEADER = "email,last_name,first_name,middle_name,faculty_code,group_code\n";
    private tools.jackson.databind.JsonNode csv(String data, int status) {
        return adminWire().upload("/api/v1/admin/import/STUDENTS", "students.csv", data.getBytes(StandardCharsets.UTF_8), status);
    }
    private String row(String suffix, String last, String first, String middle, String targetGroup) {
        return email(suffix) + "," + last + "," + first + "," + middle + "," + prefix + "f1," + prefix + targetGroup + "\n";
    }

    @Test @org.junit.jupiter.api.DisplayName("Covers AE12: CSV imports without middle name")
    void importsWithoutMiddleName() {
        var result = csv(HEADER + row("orlov", "Орлова", "Вера", "", "g1"), 200);
        assertThat(result.path("created").asInt()).isEqualTo(1);
        var user = items(get("/api/v1/admin/users?search=" + email("orlov"))).getFirst();
        assertThat(user.path("fullName").asText()).isEqualTo("Орлова Вера");
        assertThat(user.path("middleName").isNull()).isTrue();
        assertThat(user.at("/group/id").asLong()).isEqualTo(group);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "Covers AE13, AE14: reject header {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "email,last_name,first_name,faculty_code,group_code", "email,full_name,faculty_code,group_code"
    })
    void rejectsWholeFileWithWrongHeader(String header) {
        String values = header.contains("full_name") ? email("old") + ",Орлова Вера," + prefix + "f1," + prefix + "g1\n"
                : email("old") + ",Орлова,Вера," + prefix + "f1," + prefix + "g1\n";
        var before = jdbc.queryForObject("select count(*) from app_user", Integer.class);
        assertThat(csv(header + "\n" + values, 400).path("detail").asText()).isEqualTo("Не удалось прочитать файл: проверьте формат и заголовок");
        assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isEqualTo(before);
    }

    @Test @org.junit.jupiter.api.DisplayName("Covers AE15: ordered errors use exact R15 text")
    void nameErrorsInRowOrder() {
        var result = csv(HEADER + row("empty-last", "", "Иван", "", "g1") + row("empty-first", "Иванов", "", "", "g1")
                + row("long-last", "Я".repeat(101), "Иван", "", "g1") + row("long-first", "Иванов", "Я".repeat(101), "", "g1")
                + row("long-middle", "Иванов", "Иван", "Я".repeat(101), "g1"), 200);
        assertThat(result.path("created").asInt()).isZero();
        assertThat(result.path("rejected").valueStream().map(n -> n.path("reason").asText()).toList()).containsExactly(
                "пустое значение last_name", "пустое значение first_name", "неверная фамилия", "неверное имя", "неверное отчество");
        assertThat(result.path("rejected").valueStream().map(n -> n.path("row").asInt()).toList()).containsExactly(2, 3, 4, 5, 6);
        assertThat(jdbc.queryForObject("select count(*) from app_user where email like ? and role='STUDENT'", Integer.class, prefix + "%")).isZero();
    }

    @Test @org.junit.jupiter.api.DisplayName("Covers AE16: case insensitive match skips without changing data")
    void caseInsensitiveMatch() {
        csv(HEADER + row("existing", "Иванов", "Иван", "Иванович", "g1"), 200);
        var before = jdbc.queryForList("select * from app_user where email=?", email("existing"));
        var result = csv(HEADER + row("existing", "ИВАНОВ", "иван", "иванович", "g1"), 200);
        assertThat(result.path("skipped").asInt()).isEqualTo(1);
        assertThat(result.path("rejected").size()).isZero();
        assertThat(jdbc.queryForList("select * from app_user where email=?", email("existing"))).isEqualTo(before);
    }

    @Test @org.junit.jupiter.api.DisplayName("Covers AE16: empty middle matches empty middle")
    void emptyMiddleMatches() {
        csv(HEADER + row("existing", "Иванов", "Иван", "", "g1"), 200);
        assertThat(csv(HEADER + row("existing", "ИВАНОВ", "иван", "", "g1"), 200).path("skipped").asInt()).isEqualTo(1);
    }

    @Test @org.junit.jupiter.api.DisplayName("Covers AE17: different middle is email taken")
    void differentMiddleIsConflict() {
        csv(HEADER + row("existing", "Иванов", "Иван", "Иванович", "g1"), 200);
        var result = csv(HEADER + row("existing", "Иванов", "Иван", "", "g1"), 200);
        assertThat(result.at("/rejected/0/reason").asText()).isEqualTo("email занят");
        assertThat(result.path("created").asInt()).isZero();
        assertThat(jdbc.queryForObject("select middle_name from app_user where email=?", String.class, email("existing"))).isEqualTo("Иванович");
    }

    @Test @org.junit.jupiter.api.DisplayName("Covers AE17: same name in another group is email taken")
    void differentGroupIsConflict() {
        csv(HEADER + row("existing", "Иванов", "Иван", "", "g1"), 200);
        assertThat(csv(HEADER + row("existing", "Иванов", "Иван", "", "g2"), 200).at("/rejected/0/reason").asText()).isEqualTo("email занят");
        assertThat(jdbc.queryForObject("select group_id from group_membership where student_id=(select id from app_user where email=?)", Long.class, email("existing"))).isEqualTo(group);
    }

    @Test @org.junit.jupiter.api.DisplayName("Covers AE18: namesake with different email is created")
    void namesakeIsAllowed() {
        csv(HEADER + row("first", "Иванов", "Иван", "", "g1"), 200);
        assertThat(csv(HEADER + row("second", "Иванов", "Иван", "", "g1"), 200).path("created").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from app_user where email in (?,?)", Integer.class, email("first"), email("second"))).isEqualTo(2);
    }
}

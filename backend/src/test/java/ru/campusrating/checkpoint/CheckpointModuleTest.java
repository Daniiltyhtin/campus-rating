package ru.campusrating.checkpoint;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.campusrating.identity.CurrentUser;
import ru.campusrating.identity.GroupMembers;
import ru.campusrating.identity.GroupMembershipState;
import ru.campusrating.identity.MembershipActivated;
import ru.campusrating.identity.StudentMember;
import ru.campusrating.identity.TeacherScope;
import ru.campusrating.settings.SettingsApi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ApplicationModuleTest(extraIncludes = "shared")
@TestPropertySource(properties = "campus.mail.email-verification=off")
class CheckpointModuleTest {
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) throws IOException {
        var directory = Files.createTempDirectory("checkpoint-module-storage-");
        properties.add("campus.storage.dir", directory::toString);
    }

    @Autowired JdbcTemplate jdbc;
    @MockitoBean CurrentUser current;
    @MockitoBean TeacherScope scope;
    @MockitoBean GroupMembers members;
    @MockitoBean SettingsApi settings;

    @Test
    void bothEventsCreateAssignmentsOnlyOnce(Scenario scenario) {
        long faculty =
                jdbc.queryForObject(
                        "insert into faculty(code,name) values ('checkpoint-module','ФИТ') returning id",
                        Long.class);
        long group =
                jdbc.queryForObject(
                        "insert into study_group(code,name,faculty_id,admission_year) values ('checkpoint-module','ИВТ',?,2026) returning id",
                        Long.class,
                        faculty);
        long teacher = user("TEACHER");
        long student = user("STUDENT");
        var at = Instant.parse("2026-11-07T09:00:00Z");
        long point =
                jdbc.queryForObject(
                        "insert into checkpoint(author_id,title,description,deadline,cost,penalty_step,penalty_interval_seconds,created_at,updated_at) values (?,'Точка','',?,60,20,60,?,?) returning id",
                        Long.class,
                        teacher,
                        Timestamp.from(at.plusSeconds(600)),
                        Timestamp.from(at),
                        Timestamp.from(at));
        jdbc.update("insert into checkpoint_group values (?,?)", point, group);
        var member = new StudentMember(student, "Студент", group, GroupMembershipState.ACTIVE, at);
        doReturn(List.of(member)).when(members).activeStudents(List.of(group));
        doReturn(Optional.of(member)).when(members).membership(student);
        try {
            scenario.publish(new CheckpointCreated(point))
                    .andWaitAtMost(Duration.ofSeconds(10))
                    .andWaitForStateChange(() -> count(point), n -> n == 1)
                    .andVerify(n -> assertThat(n).isEqualTo(1));
            scenario.publish(new MembershipActivated(student, group, at))
                    .andWaitAtMost(Duration.ofSeconds(10))
                    .andWaitForStateChange(
                            () ->
                                    jdbc.queryForObject(
                                            "select count(*) from event_publication where event_type=? and completion_date is not null",
                                            Integer.class,
                                            MembershipActivated.class.getName()),
                            n -> n > 0)
                    .andVerify(n -> assertThat(count(point)).isEqualTo(1));
        } finally {
            jdbc.update("delete from assignment where checkpoint_id=?", point);
            jdbc.update("delete from checkpoint_group where checkpoint_id=?", point);
            jdbc.update("delete from checkpoint where id=?", point);
            jdbc.update("delete from app_user where id in (?,?)", teacher, student);
            jdbc.update("delete from study_group where id=?", group);
            jdbc.update("delete from faculty where id=?", faculty);
        }
    }

    int count(long id) {
        return jdbc.queryForObject(
                "select count(*) from assignment where checkpoint_id=?", Integer.class, id);
    }

    long user(String role) {
        return jdbc.queryForObject(
                "insert into app_user(email,role,full_name) values (?,?, 'Имя') returning id",
                Long.class,
                UUID.randomUUID() + "@test.invalid",
                role);
    }
}

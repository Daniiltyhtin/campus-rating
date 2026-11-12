package ru.campusrating.settings;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import ru.campusrating.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@TestPropertySource(properties = "campus.penalty.default-step=20")
class SettingsIT extends PostgresIntegrationTest {
    @Autowired private SettingsApi settings;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void databaseChangesAreVisibleAtNextUse() {
        jdbc.update("delete from settings");
        assertThat(settings.penaltyStep()).isEqualTo(20);
        jdbc.update("insert into settings (key, value, type) values ('penaltyStep', '25', 'INTEGER')");
        assertThat(settings.penaltyStep()).isEqualTo(25);
        jdbc.update("update settings set value = '40' where key = 'penaltyStep'");
        assertThat(settings.penaltyStep()).isEqualTo(40);
        jdbc.update("insert into settings (key, value, type) values ('penaltyInterval', 'PT4S', 'DURATION')");
        assertThat(settings.penaltyInterval()).isEqualTo(java.time.Duration.ofSeconds(4));
        jdbc.update("delete from settings");
        assertThat(settings.penaltyStep()).isEqualTo(20);
    }
}

package ru.campusrating.settings;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.campusrating.settings.internal.SettingsDefaults;
import ru.campusrating.settings.internal.SettingsService;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsTest {
    private final Map<String, String> values = new HashMap<>();
    private final SettingsApi settings = new SettingsService(key -> Optional.ofNullable(values.get(key)),
            new SettingsDefaults(new SettingsDefaults.Penalty(20, Duration.ofMinutes(1), Duration.ofSeconds(1)),
                    new SettingsDefaults.Semester(200), new SettingsDefaults.Budget(1000)));

    @Test
    void missingValuesUseConfigurationDefaults() {
        assertDefaults();
    }

    private void assertDefaults() {
        assertThat(settings.penaltyStep()).isEqualTo(20);
        assertThat(settings.penaltyInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(settings.penaltyPollPeriod()).isEqualTo(Duration.ofSeconds(1));
        assertThat(settings.minPenaltyInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(settings.semesterAccrual()).isEqualTo(200);
        assertThat(settings.monthlyBudgetLimit()).isEqualTo(1000);
    }

    @Test
    void everyCallReadsCurrentValueAndDeletionRestoresDefault() {
        assertDefaults();
        values.put("penaltyStep", "30");
        values.put("penaltyInterval", "PT10S");
        values.put("penaltyPollPeriod", "PT2S");
        values.put("semesterAccrual", "150");
        values.put("monthlyBudgetLimit", "700");
        assertThat(settings.penaltyStep()).isEqualTo(30);
        assertThat(settings.penaltyInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.minPenaltyInterval()).isEqualTo(Duration.ofSeconds(4));
        assertThat(settings.semesterAccrual()).isEqualTo(150);
        assertThat(settings.monthlyBudgetLimit()).isEqualTo(700);
        values.put("penaltyStep", "40");
        values.put("penaltyPollPeriod", "PT3S");
        assertThat(settings.penaltyStep()).isEqualTo(40);
        assertThat(settings.penaltyPollPeriod()).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.minPenaltyInterval()).isEqualTo(Duration.ofSeconds(6));
        values.put("penaltyInterval", "PT20S");
        values.put("semesterAccrual", "300");
        values.put("monthlyBudgetLimit", "900");
        assertThat(settings.penaltyInterval()).isEqualTo(Duration.ofSeconds(20));
        assertThat(settings.semesterAccrual()).isEqualTo(300);
        assertThat(settings.monthlyBudgetLimit()).isEqualTo(900);
        values.clear();
        assertDefaults();
    }

    @Test
    void configuredDefaultsCanAcceleratePenaltyWithoutDatabaseRows() {
        var accelerated = new SettingsService(key -> Optional.empty(), new SettingsDefaults(
                new SettingsDefaults.Penalty(10, Duration.ofSeconds(4), Duration.ofMillis(500)),
                new SettingsDefaults.Semester(100), new SettingsDefaults.Budget(500)));
        assertThat(accelerated.penaltyStep()).isEqualTo(10);
        assertThat(accelerated.penaltyInterval()).isEqualTo(Duration.ofSeconds(4));
        assertThat(accelerated.minPenaltyInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(accelerated.semesterAccrual()).isEqualTo(100);
        assertThat(accelerated.monthlyBudgetLimit()).isEqualTo(500);
    }
}

package ru.campusrating.activity.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AwardCalculatorTest {
    @ParameterizedTest
    @CsvSource({"75,50,38", "100,25,25", "2,25,1", "10000,100,10000", "75,75,56"})
    void roundsHalfUp(int maximum, int percent, int expected) {
        assertThat(AwardCalculator.amount(maximum, ParticipationLevel.of(percent))).isEqualTo(expected);
    }

    @Test
    void rejectsInvalidLevelsAndMaximums() {
        assertThatThrownBy(() -> ParticipationLevel.of(30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AwardCalculator.amount(1, ParticipationLevel.of(25))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AwardCalculator.amount(10001, ParticipationLevel.of(100))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsCurrency() {
        assertThat(ActivityType.ACADEMIC.currency().name()).isEqualTo("BUA");
        assertThat(ActivityType.SOCIAL.currency().name()).isEqualTo("BOA");
    }
}

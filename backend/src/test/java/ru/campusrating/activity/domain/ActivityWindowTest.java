package ru.campusrating.activity.domain;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ActivityWindowTest {
    private static final Instant START = Instant.parse("2026-11-14T07:00:00Z");
    @Test void socialEnrollmentClosesExactlyAtStart() {
        assertThat(ActivityWindow.at(ActivityType.SOCIAL, START, START.minusNanos(1)))
                .isEqualTo(new ActivityWindow(true, false));
        assertThat(ActivityWindow.at(ActivityType.SOCIAL, START, START))
                .isEqualTo(new ActivityWindow(false, true));
        assertThat(ActivityWindow.at(ActivityType.SOCIAL, START, START.plusNanos(1)))
                .isEqualTo(new ActivityWindow(false, true));
    }
    @Test void academicNeverOpensEnrollment() {
        assertThat(ActivityWindow.at(ActivityType.ACADEMIC, START, START.minusNanos(1)))
                .isEqualTo(new ActivityWindow(false, false));
        assertThat(ActivityWindow.at(ActivityType.ACADEMIC, START, START))
                .isEqualTo(new ActivityWindow(false, true));
    }
}

package ru.campusrating.checkpoint.domain;

import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.campusrating.checkpoint.AssignmentState;
import ru.campusrating.checkpoint.AssignmentStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentStatusPolicyTest {
    private static final Instant DEADLINE = Instant.parse("2026-11-02T12:00:00+03:00");

    @ParameterizedTest(name = "example {0}")
    @CsvSource({
            "1, OPEN, -1, IN_PROGRESS", "2, OPEN, 0, IN_PROGRESS",
            "3, OPEN, 90, OVERDUE", "4, OPEN, 180, OVERDUE",
            "5, OPEN, 90, OVERDUE", "6, SUBMITTED, 90, SUBMITTED",
            "accepted-before, ACCEPTED, -1, ACCEPTED", "accepted-after, ACCEPTED, 1, ACCEPTED",
            "submitted-before, SUBMITTED, -1, SUBMITTED"
    })
    void examples(String example, AssignmentState state, long seconds, AssignmentStatus expected) {
        assertThat(AssignmentStatusPolicy.status(state, DEADLINE, DEADLINE.plusSeconds(seconds)))
                .isEqualTo(expected);
    }
}

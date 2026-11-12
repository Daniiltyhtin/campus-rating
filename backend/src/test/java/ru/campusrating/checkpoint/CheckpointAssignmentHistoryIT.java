package ru.campusrating.checkpoint;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckpointAssignmentHistoryIT extends SubmissionWithdrawalSupport {
    @Test
    void rowWithoutVersionsHasNoSubmissions() {
        assertThat(row().path("hasSubmissions").isBoolean()).isTrue();
        assertThat(row().path("hasSubmissions").asBoolean()).isFalse();
    }

    @Test
    void rowWithOnlyWithdrawnVersionHasSubmissions() {
        submit();
        withdraw().expect(200);
        var result = row();
        assertThat(result.path("hasSubmissions").asBoolean()).isTrue();
        assertThat(result.path("submittedAt").isNull()).isTrue();
        assertThat(result.path("submissionKind").isNull()).isTrue();
    }

    @Test
    void rowWithCurrentVersionHasSubmissions() {
        submit();
        assertThat(row().path("hasSubmissions").asBoolean()).isTrue();
        assertThat(row().path("submissionKind").asText()).isEqualTo("LINK");
    }
}

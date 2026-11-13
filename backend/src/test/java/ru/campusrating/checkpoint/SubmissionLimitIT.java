package ru.campusrating.checkpoint;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

class SubmissionLimitIT extends SubmissionWithdrawalSupport {
    private static final String LIMIT_DETAIL = "Достигнут предел: 20 сдач по одной задаче. Обратитесь к преподавателю.";

    @Test
    void twentiethVersionIsAcceptedAndTwentyFirstIsRejectedWithoutStoringFile() throws Exception {
        storeWithdrawnVersions(19);
        var twentieth = fileReply("twentieth.pdf").expect(200);
        assertThat(twentieth.path("state").asText()).isEqualTo("SUBMITTED");
        assertThat(versions()).isEqualTo(20);
        clock.advance(Duration.ofSeconds(1));
        withdraw().expect(200);
        var before = storedFiles();

        problem(fileReply("twenty-first.pdf"), 409, "SUBMISSION_LIMIT_REACHED", LIMIT_DETAIL);
        problem(linkReply(), 409, "SUBMISSION_LIMIT_REACHED", LIMIT_DETAIL);

        assertThat(storedFiles()).containsExactlyInAnyOrderElementsOf(before);
        assertThat(versions()).isEqualTo(20);
        assertThat(read().path("state").asText()).isEqualTo("OPEN");
    }

    @Test
    void submittedWorkStillReportsInvalidStateAtTheLimit() {
        storeWithdrawnVersions(19);
        submit();
        problem(linkReply(), 409, "INVALID_STATE", null);
    }

    private void storeWithdrawnVersions(int count) {
        for (int index = 0; index < count; index++) {
            var at = Timestamp.from(clock.instant().minusSeconds(count - index));
            jdbc.update("insert into submission(assignment_id,kind,url,submitted_at,withdrawn_at) values (?,'LINK',?,?,?)",
                    assignment, "https://example.org/" + index, at, at);
        }
    }

    private int versions() {
        return jdbc.queryForObject("select count(*) from submission where assignment_id=?", Integer.class, assignment);
    }

    private List<Path> storedFiles() throws Exception {
        if (!Files.exists(storage)) return List.of();
        try (var files = Files.walk(storage)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    private Reply fileReply(String name) {
        byte[] bytes = new byte[64];
        System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 5);
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return name; }
        });
        return owner.multipart(path() + "/submission", parts);
    }

    private Reply linkReply() {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("link", "https://example.org/limit");
        return owner.multipart(path() + "/submission", parts);
    }
}

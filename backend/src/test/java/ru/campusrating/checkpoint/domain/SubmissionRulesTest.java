package ru.campusrating.checkpoint.domain;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubmissionRulesTest {
    @ParameterizedTest
    @ValueSource(strings = {"http://example.org", "https://example.org/path?q=1", "HTTPS://example.org"})
    void validLinks(String link) {
        assertThatCode(() -> SubmissionRules.validateLink(link)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "ftp://example.org", "https:///path", "https:path", "/path",
            "https://", "https://exa mple.org", "https://?q=1"})
    void invalidLinks(String link) {
        assertReason(() -> SubmissionRules.validateLink(link), SubmissionRules.Reason.LINK_INVALID);
    }

    @Test
    void linkLengthBoundary() {
        String link = "https://example.org/";
        String maximum = link + "a".repeat(2000 - link.length());
        assertThatCode(() -> SubmissionRules.validateLink(maximum)).doesNotThrowAnyException();
        assertReason(() -> SubmissionRules.validateLink(maximum + "a"), SubmissionRules.Reason.LINK_INVALID);
    }

    @ParameterizedTest
    @CsvSource({
            "report.pdf,255044462d,application/pdf", "report.PDF,255044462d,application/pdf",
            ".pdf,255044462d,application/pdf",
            "report.docx,504b0304,application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "report.DOCX,504b0304,application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "report.zip,504b0304,application/zip", "empty.zip,504b0506,application/zip",
            "image.png,89504e470d0a1a0a,image/png", "image.PNG,89504e470d0a1a0a,image/png",
            "image.jpg,ffd8ff,image/jpeg", "image.jpeg,ffd8ff,image/jpeg", "image.JPEG,ffd8ff,image/jpeg"
    })
    void matchingFileType(String name, String hex, String contentType) {
        assertThat(SubmissionRules.contentType(name, HexFormat.of().parseHex(hex))).isEqualTo(contentType);
    }

    @ParameterizedTest
    @CsvSource({"report.exe,4d5a", "report.EXE,255044462d", "report.pdf,89504e470d0a1a0a",
            "renamed.pdf,4d5a", "report.png,255044462d", "report.jpg,504b0304",
            "report.zip,ffd8ff", "report.docx,504b0506", "report,255044462d",
            "report.pdf.exe,255044462d", "PDF,255044462d", "JPEG,ffd8ff", "report.pdf,25504446", "report.pdf,",
            "report.png,89504e470d0a1a00", "report.jpg,ffd800", "report.zip,504b0300", ",255044462d"})
    void invalidFileType(String name, String hex) {
        byte[] header = hex == null ? new byte[0] : HexFormat.of().parseHex(hex);
        assertReason(() -> SubmissionRules.contentType(name, header), SubmissionRules.Reason.FILE_TYPE_NOT_ALLOWED);
    }

    @Test
    void missingHeaderIsRejected() {
        assertReason(() -> SubmissionRules.contentType("report.pdf", null),
                SubmissionRules.Reason.FILE_TYPE_NOT_ALLOWED);
    }

    @Test
    void negativeSizeIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> SubmissionRules.validateFileSize(-1));
    }

    @Test
    void fileSizeBoundary() {
        assertThatCode(() -> SubmissionRules.validateFileSize(10L * 1024 * 1024)).doesNotThrowAnyException();
        assertThatCode(() -> SubmissionRules.validateFileSize(0)).doesNotThrowAnyException();
        assertReason(() -> SubmissionRules.validateFileSize(10L * 1024 * 1024 + 1),
                SubmissionRules.Reason.FILE_TOO_LARGE);
    }

    private void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                              SubmissionRules.Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(SubmissionRules.Rejected.class,
                error -> assertThat(error.reason()).isEqualTo(reason));
    }
}

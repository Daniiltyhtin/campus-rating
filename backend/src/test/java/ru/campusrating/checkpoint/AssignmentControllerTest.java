package ru.campusrating.checkpoint;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.campusrating.checkpoint.api.AssignmentController;
import ru.campusrating.checkpoint.domain.AssignmentTransitions;
import ru.campusrating.checkpoint.domain.SubmissionRules;
import ru.campusrating.checkpoint.internal.SubmissionService;
import ru.campusrating.shared.error.ApiExceptionHandler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssignmentControllerTest {
    MockMvc mvc;
    SubmissionService service;

    @BeforeEach
    void setup() {
        service = mock(SubmissionService.class);
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new AssignmentController(service, new ApiExceptionHandler()))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .build();
    }

    @Test
    void multipartRejectsBothOrNeitherPart() throws Exception {
        var empty = MockMvcRequestBuilders.multipart("/api/v1/assignments/1/submission");
        assertThat(mvc.perform(empty).andReturn().getResponse().getStatus()).isEqualTo(400);
        var both =
                MockMvcRequestBuilders.multipart("/api/v1/assignments/1/submission")
                        .param("link", "https://example.org")
                        .file(
                                new MockMultipartFile(
                                        "file", "report.pdf", "application/pdf", new byte[0]));
        assertThat(mvc.perform(both).andReturn().getResponse().getStatus()).isEqualTo(400);
        verifyNoInteractions(service);
    }

    @Test
    void multipartPassesLinkAndFileSeparately() throws Exception {
        mvc.perform(
                MockMvcRequestBuilders.multipart("/api/v1/assignments/1/submission")
                        .param("link", "https://example.org"));
        verify(service).submit(1, "https://example.org", null);
        var file = new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[0]);
        mvc.perform(
                MockMvcRequestBuilders.multipart("/api/v1/assignments/1/submission").file(file));
        verify(service).submit(1, null, file);
    }

    @Test
    void stateFailuresUseRussianDetailsAndProblemCode() throws Exception {
        for (var state : List.of(AssignmentState.SUBMITTED, AssignmentState.ACCEPTED)) {
            var failure = catchThrowable(() -> AssignmentTransitions.submit(state));
            doThrow(failure).when(service).submit(1, "https://example.org", null);
            var response =
                    mvc.perform(
                                    MockMvcRequestBuilders.multipart(
                                                    "/api/v1/assignments/1/submission")
                                            .param("link", "https://example.org"))
                            .andReturn()
                            .getResponse();
            assertThat(response.getStatus()).isEqualTo(409);
            String detail =
                    state == AssignmentState.ACCEPTED ? "Работа уже принята" : "Работа уже сдана";
            assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                    .contains("INVALID_STATE", detail);
        }
    }

    @Test
    void rejectedLinkReportsFieldError() throws Exception {
        var failure = catchThrowable(() -> SubmissionRules.validateLink("ftp://example.org"));
        doThrow(failure).when(service).submit(1, "ftp://example.org", null);
        var response =
                mvc.perform(
                                MockMvcRequestBuilders.multipart("/api/v1/assignments/1/submission")
                                        .param("link", "ftp://example.org"))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString())
                .contains("VALIDATION_FAILED", "fieldErrors", "link");
    }

    @Test
    void fileHeadersContainAsciiFallbackUtf8NameAndStoredType() throws Exception {
        var resource = new ByteArrayResource(new byte[] {1, 2});
        doReturn(new SubmissionService.Download(resource, "отчёт.pdf", "application/pdf", 2))
                .when(service)
                .download(1, null);
        var response =
                mvc.perform(MockMvcRequestBuilders.get("/api/v1/assignments/1/submission/file"))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(response.getHeader("Content-Disposition"))
                .isEqualTo(
                        "attachment; filename=\"_____.pdf\";"
                            + " filename*=UTF-8''%D0%BE%D1%82%D1%87%D1%91%D1%82.pdf");
        assertThat(response.getContentAsByteArray()).containsExactly(1, 2);
    }
}

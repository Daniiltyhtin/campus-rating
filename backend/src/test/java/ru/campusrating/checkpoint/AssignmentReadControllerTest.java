package ru.campusrating.checkpoint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.campusrating.checkpoint.api.AssignmentReadController;
import ru.campusrating.checkpoint.internal.AssignmentReadService;
import ru.campusrating.checkpoint.internal.StudentAssignmentsService;
import ru.campusrating.shared.error.ApiExceptionHandler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssignmentReadControllerTest {
    final StudentAssignmentsService students = mock(StudentAssignmentsService.class);
    final AssignmentReadService assignments = mock(AssignmentReadService.class);
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc =
                MockMvcBuilders.standaloneSetup(new AssignmentReadController(students, assignments))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .build();
    }

    @Test
    void requiresViewAndForwardsRawActivePaginationForServiceToIgnore() throws Exception {
        var missing =
                mvc.perform(MockMvcRequestBuilders.get("/api/v1/me/assignments"))
                        .andReturn()
                        .getResponse();
        assertThat(missing.getStatus()).isEqualTo(400);
        assertThat(missing.getContentAsString()).contains("view");
        verifyNoInteractions(students);
        mvc.perform(
                MockMvcRequestBuilders.get(
                        "/api/v1/me/assignments?view=active&cursor=ignored&limit=ignored"));
        verify(students).mine("active", "ignored", "ignored");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "status=",
                "status=OPEN",
                "status=unknown",
                "groupId=0",
                "limit=0",
                "limit=101",
                "limit=nan"
            })
    void invalidFiltersReturnTheirField(String query) throws Exception {
        var response =
                mvc.perform(
                                MockMvcRequestBuilders.get(
                                        "/api/v1/checkpoints/1/assignments?" + query))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains(query.substring(0, query.indexOf('=')));
        verifyNoInteractions(assignments);
    }

    @Test
    void forwardsDefaultLimitAndTypedStatus() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/checkpoints/1/assignments"));
        verify(assignments).table(1, null, null, null, 20);
        mvc.perform(
                MockMvcRequestBuilders.get(
                        "/api/v1/checkpoints/1/assignments?status=OVERDUE&groupId=2&limit=100&cursor=opaque"));
        verify(assignments).table(1, AssignmentStatus.OVERDUE, 2L, "opaque", 100);
    }
}

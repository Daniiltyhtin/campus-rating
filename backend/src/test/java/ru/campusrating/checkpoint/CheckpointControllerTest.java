package ru.campusrating.checkpoint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.campusrating.checkpoint.api.CheckpointController;
import ru.campusrating.checkpoint.internal.CheckpointService;
import ru.campusrating.settings.SettingsApi;
import ru.campusrating.shared.error.ApiExceptionHandler;

import static org.mockito.Mockito.*;

class CheckpointControllerTest {
    MockMvcTester mvc;
    CheckpointService service;

    @BeforeEach
    void setup() {
        service = mock(CheckpointService.class);
        mvc =
                MockMvcTester.create(
                        MockMvcBuilders.standaloneSetup(
                                        new CheckpointController(
                                                service,
                                                mock(SettingsApi.class),
                                                new ApiExceptionHandler()))
                                .setControllerAdvice(new ApiExceptionHandler())
                                .build());
    }

    @Test
    void unknownPatchFieldReportsItsName() {
        mvc.patch()
                .uri("/api/v1/checkpoints/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deadline\":null}")
                .assertThat()
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.fieldErrors[0].field")
                .isEqualTo("deadline");
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "description"})
    void explicitNullReportsField(String field) {
        mvc.patch()
                .uri("/api/v1/checkpoints/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"" + field + "\":null}")
                .assertThat()
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.fieldErrors[0].field")
                .isEqualTo(field);
        verifyNoInteractions(service);
    }

    @Test
    void fractionalCostIsNotRounded() {
        mvc.post()
                .uri("/api/v1/checkpoints")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        """
                {"title":"Название","description":"","groupIds":[11],"deadline":"2030-01-01T00:00:00Z","cost":1.5}
                """)
                .assertThat()
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.fieldErrors[0].field")
                .isEqualTo("cost");
        verifyNoInteractions(service);
    }

    @Test
    void blankTitleIsInvalid() {
        mvc.patch()
                .uri("/api/v1/checkpoints/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"  \"}")
                .assertThat()
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.fieldErrors[0].field")
                .isEqualTo("title");
        verifyNoInteractions(service);
    }
}

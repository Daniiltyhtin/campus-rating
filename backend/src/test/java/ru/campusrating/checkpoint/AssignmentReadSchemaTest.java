package ru.campusrating.checkpoint;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.configuration.SpringDocSpecPropertiesConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.campusrating.checkpoint.api.AssignmentReadController;
import ru.campusrating.checkpoint.api.CheckpointAssignmentPage;
import ru.campusrating.checkpoint.api.StudentTaskPage;
import ru.campusrating.checkpoint.internal.AssignmentReadService;
import ru.campusrating.checkpoint.internal.StudentAssignmentsService;
import ru.campusrating.support.SubmissionSchemaConfiguration;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AssignmentReadSchemaTest {
    @Test
    void taskAndPageRequireAllContractFieldsIncludingNullableOnes() {
        var schemas = ModelConverters.getInstance(true).readAll(StudentTaskPage.class);
        var page = schemas.get("StudentTaskPage");
        assertThat(page.getRequired())
                .containsExactlyInAnyOrder("items", "nextCursor", "membershipState");
        assertThat(property(page, "nextCursor").getTypes())
                .containsExactlyInAnyOrder("string", "null");
        var task = schemas.get("StudentTask");
        assertThat(task.getRequired())
                .containsExactlyInAnyOrder(
                        "assignmentId",
                        "checkpoint",
                        "teacher",
                        "deadline",
                        "cost",
                        "state",
                        "status",
                        "penalty",
                        "acceptedAt");
        assertThat(property(task, "acceptedAt").getTypes())
                .containsExactlyInAnyOrder("string", "null");
        assertThat(property(task, "assignmentId").getMinimum()).isEqualByComparingTo("1");
    }

    @Test
    void springdocTableKindUsesInlineNullAsInContract() {
        new WebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                JacksonAutoConfiguration.class,
                                WebMvcAutoConfiguration.class,
                                SpringDocConfiguration.class,
                                SpringDocWebMvcConfiguration.class,
                                SpringDocSpecPropertiesConfiguration.class))
                .withUserConfiguration(
                        SubmissionSchemaConfiguration.class, SpringDocConfigProperties.class)
                .withBean(
                        StudentAssignmentsService.class,
                        () -> mock(StudentAssignmentsService.class))
                .withBean(AssignmentReadService.class, () -> mock(AssignmentReadService.class))
                .withBean(AssignmentReadController.class)
                .withPropertyValues("springdoc.default-produces-media-type=application/json")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var response =
                                    MockMvcBuilders.webAppContextSetup(context)
                                            .build()
                                            .perform(MockMvcRequestBuilders.get("/v3/api-docs"))
                                            .andReturn()
                                            .getResponse();
                            assertThat(response.getStatus()).isEqualTo(200);
                            var document = new JsonMapper().readTree(response.getContentAsString());
                            var contract =
                                    new YAMLMapper()
                                            .readTree(
                                                    Files.readString(
                                                            Path.of(
                                                                    "../docs/spec/api/openapi.yaml")));
                            var row = document.at("/components/schemas/CheckpointAssignmentRow");
                            assertThat(row.path("required"))
                                    .extracting(node -> node.asText())
                                    .containsExactlyInAnyOrder(
                                            "assignmentId",
                                            "student",
                                            "group",
                                            "state",
                                            "status",
                                            "charged",
                                            "submittedAt",
                                            "submissionKind",
                                            "hasSubmissions");
                            assertThat(row.at("/properties/submittedAt/type"))
                                    .extracting(node -> node.asText())
                                    .containsExactlyInAnyOrder("string", "null");
                            assertThat(row.at("/properties/hasSubmissions/type").asText())
                                    .isEqualTo("boolean");
                            var kind = row.at("/properties/submissionKind");
                            if (kind.has("$ref")) {
                                kind = document.at(kind.path("$ref").asText().substring(1));
                            }
                            assertThat(kind.path("anyOf").size()).isEqualTo(2);
                            assertThat(kind.at("/anyOf/0"))
                                    .isEqualTo(contract.at("/components/schemas/SubmissionKind"));
                            assertThat(kind.at("/anyOf/1"))
                                    .isEqualTo(
                                            contract.at(
                                                    "/components/schemas/CheckpointAssignmentRow/properties/submissionKind/anyOf/1"));
                        });
    }

    @Test
    void countsRequireExactlyFourStatusesAndSeamHasNoSubmissionMaterial() {
        var schemas = ModelConverters.getInstance(true).readAll(CheckpointAssignmentPage.class);
        assertThat(schemas.get("CheckpointAssignmentPage").getRequired())
                .containsExactlyInAnyOrder("items", "nextCursor", "statusCounts");
        assertThat(schemas.get("StatusCounts").getRequired())
                .containsExactlyInAnyOrder("IN_PROGRESS", "SUBMITTED", "OVERDUE", "ACCEPTED");
        assertThat(StudentAssignmentView.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "assignmentId", "title", "deadline", "status", "charged", "acceptedAt");
    }

    private Schema<?> property(Schema<?> schema, String name) {
        return (Schema<?>) schema.getProperties().get(name);
    }
}

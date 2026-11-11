package ru.campusrating.support;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json31;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.campusrating.checkpoint.api.AssignmentRequests;
import ru.campusrating.checkpoint.api.AssignmentView;
import ru.campusrating.checkpoint.api.SubmissionUpload;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SubmissionSchemaTest {
    @TempDir Path temporary;
    final JsonMapper json = new JsonMapper();

    @Test
    void nativeSchemasMatchContractIncludingForbiddenMultipartPartsAndNullableSubmission()
            throws Exception {
        var converters = new ModelConverters(true);
        converters.addConverter(new SubmissionSchemaConfiguration().forbiddenSubmissionPart());
        var actualSchemas = json.createObjectNode();
        var types =
                Map.of(
                        "SubmissionUpload", SubmissionUpload.class,
                        "Assignment", AssignmentView.class,
                        "AcceptAssignmentsRequest", AssignmentRequests.Accept.class,
                        "AcceptAssignmentsResult", AssignmentRequests.Accepted.class,
                        "ReturnAssignmentRequest", AssignmentRequests.Return.class);
        var actual = document();
        var expected = document();
        var contract =
                new YAMLMapper()
                        .readTree(Files.readString(Path.of("../docs/spec/api/openapi.yaml")));
        expected.withObject("/components").set("schemas", contract.at("/components/schemas"));
        actual.withObject("/components").set("schemas", actualSchemas);
        for (var entry : types.entrySet()) {
            var resolved =
                    converters.resolveAsResolvedSchema(
                            new AnnotatedType(entry.getValue()).resolveAsRef(true));
            for (var schema : resolved.referencedSchemas.entrySet()) {
                actualSchemas.set(schema.getKey(), json.readTree(Json31.pretty(schema.getValue())));
            }
            String mediaType =
                    entry.getKey().equals("SubmissionUpload")
                            ? "multipart/form-data"
                            : "application/json";
            String position =
                    entry.getKey().endsWith("Request") || entry.getKey().equals("SubmissionUpload")
                            ? "requestBody"
                            : "responses/200";
            expected.withObject("/paths/~1" + entry.getKey() + "/post").putObject("responses");
            actual.withObject("/paths/~1" + entry.getKey() + "/post").putObject("responses");
            String pointer = "/paths/" + "~1" + entry.getKey() + "/post/" + position;
            expected.withObject(pointer + "/content/" + mediaType.replace("/", "~1") + "/schema")
                    .put("$ref", "#/components/schemas/" + entry.getKey());
            actual.withObject(pointer + "/content/" + mediaType.replace("/", "~1"))
                    .set("schema", json.readTree(Json31.pretty(resolved.schema)));
            expected.withObject("/paths/~1" + entry.getKey() + "/post/responses/200")
                    .put("description", "ok");
            actual.withObject("/paths/~1" + entry.getKey() + "/post/responses/200")
                    .put("description", "ok");
        }
        assertThat(actualSchemas.at("/Link/properties/file").isBoolean()).isTrue();
        assertThat(actualSchemas.at("/Link/properties/file").asBoolean()).isFalse();
        assertThat(actualSchemas.at("/File/properties/link").isBoolean()).isTrue();
        var baseline = temporary.resolve("expected.json");
        var revision = temporary.resolve("actual.json");
        var output = temporary.resolve("diff.json");
        json.writeValue(baseline.toFile(), expected);
        json.writeValue(revision.toFile(), actual);
        String platform =
                System.getProperty("os.name").toLowerCase().contains("windows") ? "win32" : "linux";
        var executable =
                Path.of(
                        "../.tools/oasdiff/"
                                + platform
                                + "-x64/oasdiff"
                                + (platform.equals("win32") ? ".exe" : ""));
        var process =
                new ProcessBuilder(
                                executable.toString(),
                                "breaking",
                                baseline.toString(),
                                revision.toString(),
                                "--format",
                                "json",
                                "--fail-on",
                                "WARN",
                                "--allow-external-refs=false")
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(Files.readString(output)).isZero();
        } finally {
            process.destroyForcibly();
        }
    }

    ObjectNode document() {
        return (ObjectNode)
                json.readTree(
                        "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"submission\",\"version\":\"1\"},\"paths\":{},\"components\":{}}");
    }
}

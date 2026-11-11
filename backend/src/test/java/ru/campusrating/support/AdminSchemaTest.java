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
import ru.campusrating.directory.DepartmentInfo;
import ru.campusrating.directory.FacultyInfo;
import ru.campusrating.directory.api.AdminDirectoryController.*;
import ru.campusrating.identity.api.AdminTeachersController.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSchemaTest {
    @TempDir Path temporary;

    @Test
    void adminRequestsAndResponsesConformToContract() throws Exception {
        var json = new JsonMapper();
        var contract = new YAMLMapper().readTree(Files.readString(Path.of("../docs/spec/api/openapi.yaml")));
        var actual = (ObjectNode) contract.deepCopy();
        var expected = (ObjectNode) contract.deepCopy();
        var actualPaths = actual.putObject("paths");
        var expectedPaths = expected.putObject("paths");
        var schemas = actual.putObject("components").putObject("schemas");
        var converters = new ModelConverters(true);
        converters.addConverter(new AdminSchemaConfiguration().adminSchemaConverter());
        var types = Map.ofEntries(
                Map.entry("AdminFaculty", FacultyInfo.class),
                Map.entry("AdminDepartment", DepartmentInfo.class),
                Map.entry("AdminStudyGroup", AdminStudyGroup.class),
                Map.entry("AdminTeacher", AdminTeacher.class),
                Map.entry("AdminTeacherGrant", AdminTeacherGrant.class),
                Map.entry("FacultyWrite", FacultyWrite.class),
                Map.entry("StudyGroupWrite", StudyGroupWrite.class),
                Map.entry("DepartmentWrite", DepartmentWrite.class),
                Map.entry("TeacherApprovalRequest", TeacherApprovalRequest.class),
                Map.entry("TeacherGrantCreate", TeacherGrantCreate.class));
        for (var entry : types.entrySet()) {
            var resolved = converters.resolveAsResolvedSchema(new AnnotatedType(entry.getValue()).resolveAsRef(true));
            for (var schema : resolved.referencedSchemas.entrySet()) {
                schemas.set(schema.getKey(), json.readTree(Json31.pretty(schema.getValue())));
            }
            boolean request = entry.getKey().endsWith("Write") || entry.getKey().endsWith("Request") || entry.getKey().endsWith("Create");
            String pointer = request ? "/requestBody/content/application~1json/schema" : "/responses/200/content/application~1json/schema";
            var expectedOperation = expectedPaths.putObject("/" + entry.getKey()).putObject("post");
            var actualOperation = actualPaths.putObject("/" + entry.getKey()).putObject("post");
            for (var operation : new ObjectNode[] {expectedOperation, actualOperation}) {
                operation.put("operationId", entry.getKey());
                operation.putObject("responses").putObject("200").put("description", "OK");
            }
            expectedOperation.withObject(pointer).put("$ref", "#/components/schemas/" + entry.getKey());
            actualOperation.withObject(pointer).put("$ref", resolved.schema.get$ref());
        }
        Path before = temporary.resolve("expected.json"), after = temporary.resolve("actual.json"), output = temporary.resolve("diff.json");
        json.writeValue(before.toFile(), expected);
        json.writeValue(after.toFile(), actual);
        String platform = System.getProperty("os.name").toLowerCase().contains("windows") ? "win32" : "linux";
        var executable = Path.of("../.tools/oasdiff/" + platform + "-x64/oasdiff" + (platform.equals("win32") ? ".exe" : ""));
        var process = new ProcessBuilder(executable.toString(), "breaking", before.toString(), after.toString(),
                "--format", "json", "--fail-on", "WARN", "--allow-external-refs=false")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(Files.readString(output)).isZero();
        } finally {
            process.destroyForcibly();
        }
    }
}

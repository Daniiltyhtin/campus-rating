package ru.campusrating;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import ru.campusrating.support.PostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = "springdoc.default-produces-media-type=application/json")
class ContractCompatibilityIT extends PostgresIntegrationTest {
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @LocalServerPort private int port;
    @TempDir Path temporary;

    @Test
    void implementedOperationsConformToContract() throws Exception {
        var contract = YAMLMapper.builder().build().readTree(Files.readString(Path.of("../docs/spec/api/openapi.yaml")));
        var actual = springdoc();
        requireOperations(contract, actual);
        var expectedProjection = project(contract, actual);
        var actualProjection = project(actual, actual);
        var result = compare(expectedProjection, actualProjection);
        assertThat(result.exitCode()).withFailMessage(result.output()).isZero();
    }

    @Test
    void renamedActualDtoFieldIsRejected() throws Exception {
        var actual = springdoc();
        var changed = (ObjectNode) actual.deepCopy();
        var schema = (ObjectNode) changed.path("components").path("schemas").path("FacultyInfo");
        var properties = (ObjectNode) schema.path("properties");
        assertThat(properties.has("name")).isTrue();
        properties.set("renamed", properties.remove("name"));
        var required = JSON.createArrayNode();
        for (var field : schema.path("required")) required.add(field.asText().equals("name") ? "renamed" : field.asText());
        schema.set("required", required);
        assertBreaking(project(actual, actual), project(changed, actual));
    }

    private JsonNode springdoc() throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/v3/api-docs")).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    @Test
    void operationPresenceChecksCannotBeBypassed() {
        var document = spec("{\"type\":\"string\"}", false, "application/json");
        var missing = JSON.readTree("{\"paths\":{}}");
        ((ObjectNode) document.path("paths").path("/probe").path("post")).put("x-status", "implemented");
        assertThatThrownBy(() -> requireOperations(document, missing)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> requireOperations(missing, document)).isInstanceOf(AssertionError.class);
    }

    @Test
    void comparisonDetectsBreaking31SchemasAndAllowsCompatibleChanges() throws Exception {
        var object = "{\"type\":\"object\",\"required\":[\"value\"],\"properties\":{\"value\":{\"type\":\"string\"}}}";
        var baseline = spec(object, false, "application/json");
        assertThat(compare(baseline, baseline).exitCode()).isZero();
        assertBreaking(baseline, spec(object.replace("string", "integer"), false, "application/json"));
        assertBreaking(baseline, spec(object.replace("value", "renamed"), false, "application/json"));
        assertBreaking(baseline, spec(object.replace("\"required\":[\"value\"],", ""), false, "application/json"));
        assertBreaking(spec("{\"type\":\"string\"}", false, "application/json"),
                spec("{\"type\":[\"string\",\"null\"]}", false, "application/json"));
        assertBreaking(spec("{\"oneOf\":[{\"type\":\"string\"}]}", false, "application/json"),
                spec("{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}", false, "application/json"));
        var optional = "{\"type\":\"object\",\"properties\":{\"file\":{\"type\":\"string\",\"format\":\"binary\"}}}";
        var required = optional.replace("\"properties\"", "\"required\":[\"file\"],\"properties\"");
        assertBreaking(spec(optional, true, "multipart/form-data"), spec(required, true, "multipart/form-data"));
        assertThat(compare(spec(required, true, "multipart/form-data"),
                spec(optional, true, "multipart/form-data")).exitCode()).isZero();
    }

    private void assertBreaking(JsonNode before, JsonNode after) throws Exception {
        var result = compare(before, after);
        assertThat(result.exitCode()).withFailMessage(result.output()).isEqualTo(1);
    }

    private static void requireOperations(JsonNode contract, JsonNode actual) {
        for (var path : actual.path("paths").properties()) {
            for (var method : path.getValue().properties()) {
                if (METHODS.contains(method.getKey())) {
                    assertThat(contract.path("paths").path(path.getKey()).has(method.getKey()))
                            .as("Undocumented operation %s %s", method.getKey(), path.getKey()).isTrue();
                }
            }
        }
        for (var path : contract.path("paths").properties()) {
            for (var method : path.getValue().properties()) {
                if (METHODS.contains(method.getKey()) && "implemented".equals(method.getValue().path("x-status").asText())) {
                    assertThat(actual.path("paths").path(path.getKey()).has(method.getKey()))
                            .as("Implemented operation has no controller: %s %s", method.getKey(), path.getKey()).isTrue();
                }
            }
        }
    }

    private static ObjectNode project(JsonNode source, JsonNode actual) {
        var result = (ObjectNode) source.deepCopy();
        result.remove("security");
        var paths = JSON.createObjectNode();
        for (var path : actual.path("paths").properties()) {
            var item = JSON.createObjectNode();
            var original = source.path("paths").path(path.getKey());
            if (original.has("parameters")) item.set("parameters", original.get("parameters"));
            for (var method : path.getValue().properties()) {
                if (!METHODS.contains(method.getKey())) continue;
                var operation = (ObjectNode) original.path(method.getKey()).deepCopy();
                operation.remove("security");
                var responses = JSON.createObjectNode();
                for (var response : operation.path("responses").properties()) {
                    if (response.getKey().matches("2[0-9][0-9]")) responses.set(response.getKey(), response.getValue());
                }
                operation.set("responses", responses);
                item.set(method.getKey(), operation);
            }
            paths.set(path.getKey(), item);
        }
        result.set("paths", paths);
        return result;
    }

    private Comparison compare(JsonNode before, JsonNode after) throws Exception {
        String os = System.getProperty("os.name").toLowerCase().contains("windows") ? "win32" : "linux";
        var binary = Path.of("../.tools/oasdiff/" + os + "-x64/oasdiff" + (os.equals("win32") ? ".exe" : "")).toAbsolutePath();
        assertThat(Files.isRegularFile(binary)).as("Run node tools/install-oasdiff.mjs before verify").isTrue();
        var directory = Files.createTempDirectory(temporary, "compare-");
        var base = directory.resolve("base.json");
        var revision = directory.resolve("revision.json");
        var output = directory.resolve("output.txt");
        JSON.writeValue(base.toFile(), before);
        JSON.writeValue(revision.toFile(), after);
        var process = new ProcessBuilder(List.of(binary.toString(), "breaking", base.toString(), revision.toString(),
                "--format", "json", "--fail-on", "WARN", "--allow-external-refs=false"))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("oasdiff timed out").isTrue();
            return new Comparison(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static JsonNode spec(String schema, boolean request, String media) {
        var operation = request
                ? "\"requestBody\":{\"content\":{\"" + media + "\":{\"schema\":" + schema
                    + "}}},\"responses\":{\"204\":{\"description\":\"OK\"}}"
                : "\"responses\":{\"200\":{\"description\":\"OK\",\"content\":{\"" + media
                    + "\":{\"schema\":" + schema + "}}}}";
        return JSON.readTree("{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"probe\",\"version\":\"1\"},"
                + "\"paths\":{\"/probe\":{\"post\":{\"operationId\":\"probe\"," + operation + "}}}}");
    }

    private record Comparison(int exitCode, String output) {}
}

package ru.campusrating.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractComparisonTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void identicalSpecificationsAndRenamedComponentsAreCompatible() throws Exception {
        var spec = spec("response", "{\"type\":\"object\",\"required\":[\"value\"],\"properties\":{\"value\":{\"type\":\"string\"}}}");
        ContractComparison.assertCompatible(spec, spec.deepCopy());
        var renamed = json.readTree(spec.toString().replace("Item", "ItemDto"));
        ContractComparison.assertCompatible(spec, renamed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"response", "request", "parameter"})
    void detectsTypeChanges(String part) throws Exception {
        var before = spec(part, "{\"type\":\"string\"}");
        var after = spec(part, "{\"type\":\"integer\"}");
        assertThatThrownBy(() -> ContractComparison.assertCompatible(before, after)).isInstanceOf(AssertionError.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"response", "request", "parameter"})
    void detectsNullAddedAndRemoved(String part) throws Exception {
        var plain = spec(part, "{\"type\":\"string\"}");
        var nullable = spec(part, "{\"type\":[\"string\",\"null\"]}");
        assertThatThrownBy(() -> ContractComparison.assertCompatible(plain, nullable)).hasMessageContaining("nullable");
        assertThatThrownBy(() -> ContractComparison.assertCompatible(nullable, plain)).hasMessageContaining("nullable");
    }

    @Test
    void detectsOneOfVariantAddedAndFieldTypeChanged() throws Exception {
        var before = spec("response", "{\"oneOf\":[{\"type\":\"object\",\"required\":[\"value\"],\"properties\":{\"value\":{\"type\":\"string\"}}},{\"type\":\"object\",\"required\":[\"number\"],\"properties\":{\"number\":{\"type\":\"integer\"}}}]}");
        var changed = before.deepCopy();
        ((ObjectNode) changed.at("/components/schemas/Item/oneOf/0/properties/value")).put("type", "boolean");
        assertThatThrownBy(() -> ContractComparison.assertCompatible(before, changed)).isInstanceOf(AssertionError.class);
        var added = before.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) added.at("/components/schemas/Item/oneOf"))
                .add(json.readTree("{\"type\":\"boolean\"}"));
        assertThatThrownBy(() -> ContractComparison.assertCompatible(before, added)).isInstanceOf(AssertionError.class);
    }

    @Test
    void detectsRenamedField() throws Exception {
        var before = spec("response", "{\"type\":\"object\",\"required\":[\"value\"],\"properties\":{\"value\":{\"type\":\"string\"}}}");
        var after = json.readTree(before.toString().replace("value", "renamed"));
        assertThatThrownBy(() -> ContractComparison.assertCompatible(before, after)).isInstanceOf(AssertionError.class);
    }

    @Test
    void detectsMultipartOneOfFlatteningUnlessNamedExceptionApplies() throws Exception {
        var before = spec("request", "{\"oneOf\":[{\"type\":\"object\",\"required\":[\"link\"],\"properties\":{\"link\":{\"type\":\"string\"}}},{\"type\":\"object\",\"required\":[\"file\"],\"properties\":{\"file\":{\"type\":\"string\",\"format\":\"binary\"}}}]}");
        var after = spec("request", "{\"type\":\"object\",\"properties\":{\"link\":{\"type\":\"string\"},\"file\":{\"type\":\"string\",\"format\":\"binary\"}}}");
        assertThatThrownBy(() -> ContractComparison.assertCompatible(before, after)).isInstanceOf(AssertionError.class);
        ((ObjectNode) before.at("/paths/~1items/get")).put("operationId", "submitAssignment");
        ContractComparison.assertCompatible(before, after);
    }

    @Test
    void detectsRemovedRequiredResponseAndNewRequiredInputs() throws Exception {
        var response = spec("response", "{\"type\":\"object\",\"required\":[\"value\"],\"properties\":{\"value\":{\"type\":\"string\"}}}");
        var optionalResponse = response.deepCopy();
        ((ObjectNode) optionalResponse.at("/components/schemas/Item")).remove("required");
        assertThatThrownBy(() -> ContractComparison.assertCompatible(response, optionalResponse)).isInstanceOf(AssertionError.class);
        var request = spec("request", "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}}}");
        var requiredRequest = request.deepCopy();
        ((ObjectNode) requiredRequest.at("/components/schemas/Item")).putArray("required").add("value");
        assertThatThrownBy(() -> ContractComparison.assertCompatible(request, requiredRequest)).isInstanceOf(AssertionError.class);
        var parameter = spec("parameter", "{\"type\":\"string\"}");
        var requiredParameter = parameter.deepCopy();
        ((ObjectNode) requiredParameter.at("/paths/~1items/get/parameters/0")).put("required", true);
        assertThatThrownBy(() -> ContractComparison.assertCompatible(parameter, requiredParameter)).isInstanceOf(AssertionError.class);
    }

    @Test
    void comparesFieldsNamedLikeSchemaMetadata() throws Exception {
        var before = spec("response", "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"},\"security\":{\"type\":\"string\"}}}");
        for (var field : new String[]{"title", "description", "security"}) {
            var changed = before.deepCopy();
            ((ObjectNode) changed.at("/components/schemas/Item/properties/" + field)).put("type", "integer");
            assertThatThrownBy(() -> ContractComparison.assertCompatible(before, changed)).isInstanceOf(AssertionError.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\":\"integer\",\"minimum\":1,\"maximum\":10,\"exclusiveMinimum\":2,\"exclusiveMaximum\":9}",
        "{\"type\":\"string\",\"minLength\":2,\"maxLength\":10,\"pattern\":\"[a-z]+\"}",
        "{\"type\":\"array\",\"minItems\":1,\"maxItems\":10,\"items\":{\"type\":\"integer\",\"minimum\":1}}"
    })
    void ignoresValueConstraintsOnlyInSuccessResponses(String constrained) throws Exception {
        var plain = (ObjectNode) json.readTree(constrained);
        for (var keyword : new String[]{"minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength", "pattern", "minItems", "maxItems"}) {
            plain.remove(keyword);
        }
        if (plain.has("items")) ((ObjectNode) plain.path("items")).remove("minimum");
        ContractComparison.assertCompatible(spec("response", constrained), spec("response", plain.toString()));
        ContractComparison.assertCompatible(spec("response", plain.toString()), spec("response", constrained));
    }

    @ParameterizedTest
    @ValueSource(strings = {"parameter", "request"})
    void stillComparesValueConstraintsInInputs(String part) throws Exception {
        var before = spec(part, "{\"type\":\"integer\",\"minimum\":1}");
        var after = spec(part, "{\"type\":\"integer\",\"minimum\":2}");
        assertThatThrownBy(() -> ContractComparison.assertCompatible(before, after)).isInstanceOf(AssertionError.class);
    }

    @Test
    void responsePropertyNamedMinimumIsStillCompared() throws Exception {
        var before = spec("response", "{\"type\":\"object\",\"required\":[\"minimum\"],\"properties\":{\"minimum\":{\"type\":\"string\"}}}");
        var after = before.deepCopy();
        ((ObjectNode) after.at("/components/schemas/Item/properties/minimum")).put("type", "integer");
        assertThatThrownBy(() -> ContractComparison.assertCompatible(before, after)).isInstanceOf(AssertionError.class);
    }

    @Test
    void convertsNullableBinaryAndConstWithoutLosingMeaning() throws Exception {
        var converted = ContractComparison.toOpenApi30(json.readTree("{\"type\":[\"string\",\"null\"],\"contentMediaType\":\"application/octet-stream\"}"));
        assertThat(converted.path("type").asText()).isEqualTo("string");
        assertThat(converted.path("nullable").asBoolean()).isTrue();
        assertThat(converted.path("format").asText()).isEqualTo("binary");
        assertThat(ContractComparison.toOpenApi30(json.readTree("{\"const\":\"LINK\"}")).path("enum").get(0).asText()).isEqualTo("LINK");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"type\":[\"string\",\"integer\"]}", "{\"properties\":{\"file\":false}}", "{\"const\":null}", "{\"exclusiveMinimum\":1}", "{\"unevaluatedProperties\":false}", "{\"if\":{}}", "{\"prefixItems\":[]}"})
    void unsupported31ConstructsFailExplicitly(String schema) throws Exception {
        assertThatThrownBy(() -> ContractComparison.toOpenApi30(json.readTree(schema)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OpenAPI 3.1");
    }

    @Test
    void reportsAllOperationViolationsTogether() throws Exception {
        var contract = spec("response", "{\"type\":\"string\"}");
        var actual = contract.deepCopy();
        ((ObjectNode) actual.path("paths")).set("/extra", actual.at("/paths/~1items").deepCopy());
        ((ObjectNode) actual.path("paths")).remove("/items");
        assertThatThrownBy(() -> ContractComparison.assertCompatible(contract, actual))
                .hasMessageContaining("GET /extra", "GET /items");
    }

    @Test
    void ignoresPlannedSchemasErrorsSecurityDescriptionsAndExamples() throws Exception {
        var contract = spec("response", "{\"type\":\"string\"}");
        var actual = contract.deepCopy();
        ((ObjectNode) contract.at("/paths/~1items/get")).put("x-status", "planned");
        ((ObjectNode) actual.at("/components/schemas/Item")).put("type", "integer");
        ContractComparison.assertCompatible(contract, actual);
        ((ObjectNode) contract.at("/paths/~1items/get")).put("x-status", "implemented");
        actual = contract.deepCopy();
        ((ObjectNode) actual.at("/paths/~1items/get/responses")).set("400", json.readTree("{\"description\":\"error\",\"content\":{\"application/json\":{\"schema\":false}}}"));
        ((ObjectNode) actual.at("/components/schemas/Item")).put("description", "changed");
        ContractComparison.assertCompatible(contract, actual);
    }

    private JsonNode spec(String part, String schema) throws Exception {
        var root = (ObjectNode) json.readTree("{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"test\",\"version\":\"1\"},\"paths\":{\"/items\":{\"get\":{\"operationId\":\"items\",\"x-status\":\"implemented\",\"responses\":{\"200\":{\"description\":\"ok\"}}}}},\"components\":{\"schemas\":{\"Item\":{}}}}");
        ((ObjectNode) root.at("/components/schemas")).set("Item", json.readTree(schema));
        var media = json.readTree("{\"application/json\":{\"schema\":{\"$ref\":\"#/components/schemas/Item\"}}}");
        var operation = (ObjectNode) root.at("/paths/~1items/get");
        switch (part) {
            case "response" -> ((ObjectNode) operation.at("/responses/200")).set("content", media);
            case "request" -> operation.set("requestBody", json.createObjectNode().put("required", true).set("content", media));
            case "parameter" -> operation.set("parameters", json.readTree("[{\"name\":\"q\",\"in\":\"query\",\"schema\":{\"$ref\":\"#/components/schemas/Item\"}}]"));
            default -> throw new IllegalArgumentException(part);
        }
        return root;
    }
}

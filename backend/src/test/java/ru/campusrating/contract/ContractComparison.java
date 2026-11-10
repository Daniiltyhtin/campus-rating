package ru.campusrating.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.openapitools.openapidiff.core.OpenApiCompare;
import org.openapitools.openapidiff.core.output.MarkdownRender;

public final class ContractComparison {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace");
    private static final Set<String> SCALAR_KEYWORDS = Set.of("type", "format", "nullable", "required", "enum", "default",
            "multipleOf", "maximum", "minimum", "exclusiveMaximum", "exclusiveMinimum", "maxLength", "minLength", "pattern",
            "maxItems", "minItems", "uniqueItems", "maxProperties", "minProperties", "readOnly", "writeOnly", "deprecated",
            "discriminator", "xml");
    private static final Set<String> RESPONSE_VALUE_CONSTRAINTS = Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "minLength", "maxLength", "pattern", "minItems", "maxItems");
    private static final Set<String> IGNORED = Set.of("description", "summary", "example", "examples", "externalDocs", "title", "security");

    public record ComparisonException(String operation, String omitted, String reason) {}
    public static final List<ComparisonException> EXCEPTIONS = List.of(new ComparisonException("submitAssignment", "requestBody",
            "Contract oneOf forbids the other part; springdoc @RequestPart describes a flat object"));

    private ContractComparison() {}

    public static void assertCompatible(JsonNode contract, JsonNode springdoc) {
        var expected = operations(contract);
        var actual = operations(springdoc);
        var violations = new ArrayList<String>();
        actual.keySet().stream().filter(key -> !expected.containsKey(key))
                .forEach(key -> violations.add("Outside contract: " + key));
        expected.forEach((key, operation) -> {
            if (implemented(operation) && !actual.containsKey(key)) violations.add("Missing controller: " + key);
        });
        if (!violations.isEmpty()) throw new AssertionError(String.join("\n", violations));
        expected.forEach((key, operation) -> {
            if (!implemented(operation) || !actual.containsKey(key)) return;
            var omitBody = EXCEPTIONS.stream().anyMatch(exception -> exception.operation().equals(operation.path("operationId").asText())
                    && exception.omitted().equals("requestBody"));
            var before = projection(contract, key, operation, omitBody);
            var after = projection(springdoc, key, actual.get(key), omitBody);
            var oldNullable = nullablePaths(before, "");
            var newNullable = nullablePaths(after, "");
            if (!oldNullable.equals(newNullable)) {
                violations.add(key + ": nullable changed: " + oldNullable + " -> " + newNullable);
            }
            nameOneOfBranches(before);
            nameOneOfBranches(after);
            var diff = OpenApiCompare.fromContents(before.toString(), after.toString());
            if (diff == null) throw new IllegalArgumentException("Cannot parse comparison for " + key);
            if (diff.isIncompatible()) violations.add(key + ": incompatible schemas: " + new MarkdownRender().render(diff));
        });
        if (!violations.isEmpty()) throw new AssertionError(String.join("\n", violations));
    }

    // openapi-diff 2.1.7 compares shared oneOf variants through references only.
    private static void nameOneOfBranches(ObjectNode document) {
        var schemas = JSON.createObjectNode();
        extractVariants(document.path("paths"), schemas);
        if (!schemas.isEmpty()) document.putObject("components").set("schemas", schemas);
    }

    private static void extractVariants(JsonNode node, ObjectNode schemas) {
        if (node.isArray()) {
            node.forEach(child -> extractVariants(child, schemas));
        } else if (node.isObject()) {
            node.fields().forEachRemaining(entry -> extractVariants(entry.getValue(), schemas));
            if (node.path("oneOf") instanceof ArrayNode variants) {
                var mapping = JSON.createObjectNode();
                var discriminator = node.path("discriminator").path("propertyName").asText();
                for (int i = 0; i < variants.size(); i++) {
                    var variant = variants.get(i);
                    var name = "Variant" + schemas.size();
                    schemas.set(name, variant);
                    var ref = "#/components/schemas/" + name;
                    variants.set(i, JSON.createObjectNode().put("$ref", ref));
                    var tag = variant.path("properties").path(discriminator).path("enum");
                    if (tag.isArray()) tag.forEach(value -> mapping.put(value.asText(), ref));
                }
                if (!mapping.isEmpty()) ((ObjectNode) node.path("discriminator")).set("mapping", mapping);
            }
        }
    }

    private static boolean implemented(JsonNode operation) {
        return operation.path("x-status").asText().equals("implemented");
    }

    private static Map<String, JsonNode> operations(JsonNode document) {
        var result = new LinkedHashMap<String, JsonNode>();
        document.path("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(method -> {
            if (METHODS.contains(method.getKey())) result.put(method.getKey().toUpperCase() + " " + path.getKey(), method.getValue());
        }));
        return result;
    }

    private static ObjectNode projection(JsonNode document, String key, JsonNode operation, boolean omitBody) {
        var result = JSON.createObjectNode().put("openapi", "3.0.3");
        result.set("info", JSON.createObjectNode().put("title", "comparison").put("version", "1"));
        var projected = JSON.createObjectNode();
        var parts = key.split(" ", 2);
        var parameters = JSON.createArrayNode();
        addParameters(parameters, document.path("paths").path(parts[1]).path("parameters"), document);
        addParameters(parameters, operation.path("parameters"), document);
        if (!parameters.isEmpty()) projected.set("parameters", parameters);
        if (!omitBody && operation.has("requestBody")) projected.set("requestBody", payload(operation.path("requestBody"), document, false));
        var responses = JSON.createObjectNode();
        operation.path("responses").fields().forEachRemaining(response -> {
            if (response.getKey().matches("2[0-9Xx]{2}")) responses.set(response.getKey(), payload(response.getValue(), document, true));
        });
        projected.set("responses", responses);
        result.putObject("paths").putObject(parts[1]).set(parts[0].toLowerCase(), projected);
        return result;
    }

    private static void addParameters(ArrayNode target, JsonNode parameters, JsonNode document) {
        parameters.forEach(raw -> {
            var parameter = resolve(raw, document, new HashSet<>());
            var copy = JSON.createObjectNode();
            for (var name : List.of("name", "in", "required", "style", "explode", "allowEmptyValue", "allowReserved")) {
                if (parameter.has(name)) copy.set(name, parameter.get(name));
            }
            if (parameter.has("schema")) copy.set("schema", toOpenApi30(parameter.get("schema")));
            if (parameter.has("content")) copy.set("content", media(parameter.get("content"), false));
            target.add(copy);
        });
    }

    private static ObjectNode payload(JsonNode raw, JsonNode document, boolean response) {
        var node = resolve(raw, document, new HashSet<>());
        var result = JSON.createObjectNode();
        if (response) result.put("description", "response");
        else if (node.has("required")) result.set("required", node.get("required"));
        if (node.has("content")) result.set("content", media(node.get("content"), response));
        return result;
    }

    private static ObjectNode media(JsonNode content, boolean response) {
        var result = JSON.createObjectNode();
        content.fields().forEachRemaining(entry -> {
            var value = JSON.createObjectNode();
            if (entry.getValue().has("schema")) value.set("schema", toOpenApi30(entry.getValue().get("schema"), response));
            result.set(entry.getKey(), value);
        });
        return result;
    }

    private static JsonNode resolve(JsonNode node, JsonNode document, Set<String> ancestors) {
        return resolve(node, document, ancestors, false);
    }

    private static JsonNode resolve(JsonNode node, JsonNode document, Set<String> ancestors, boolean propertyNames) {
        if (node.isArray()) {
            var result = JSON.createArrayNode();
            node.forEach(child -> result.add(resolve(child, document, ancestors)));
            return result;
        }
        if (!node.isObject()) return node.deepCopy();
        var result = JSON.createObjectNode();
        if (!propertyNames && node.has("$ref")) {
            var ref = node.path("$ref").asText();
            if (!ref.startsWith("#/")) throw new IllegalArgumentException("External reference: " + ref);
            if (ancestors.contains(ref)) throw new IllegalArgumentException("Recursive reference cannot be expanded: " + ref);
            var target = document.at(ref.substring(1));
            if (target.isMissingNode()) throw new IllegalArgumentException("Missing reference: " + ref);
            var next = new HashSet<>(ancestors);
            next.add(ref);
            result.setAll((ObjectNode) resolve(target, document, next));
        }
        node.fields().forEachRemaining(entry -> {
            if ((propertyNames || !entry.getKey().equals("$ref")) && (propertyNames || (!IGNORED.contains(entry.getKey()) && !entry.getKey().startsWith("x-")))) {
                result.set(entry.getKey(), resolve(entry.getValue(), document, ancestors, !propertyNames && entry.getKey().equals("properties")));
            }
        });
        return result;
    }

    public static JsonNode toOpenApi30(JsonNode schema) {
        return toOpenApi30(schema, false);
    }

    private static JsonNode toOpenApi30(JsonNode schema, boolean response) {
        if (!schema.isObject()) throw unsupported("boolean schema " + schema);
        var result = JSON.createObjectNode();
        schema.fields().forEachRemaining(entry -> {
            var name = entry.getKey();
            var value = entry.getValue();
            if (IGNORED.contains(name) || name.startsWith("x-") || (response && RESPONSE_VALUE_CONSTRAINTS.contains(name))) return;
            switch (name) {
                case "type" -> convertType(value, result);
                case "const" -> {
                    if (!value.isValueNode() || value.isNull()) throw unsupported("const " + value);
                    result.set("enum", JSON.createArrayNode().add(value));
                    if (!schema.has("type")) result.put("type", value.isTextual() ? "string" : value.isBoolean() ? "boolean" : value.isIntegralNumber() ? "integer" : "number");
                }
                case "contentMediaType" -> {
                    if (!schema.path("type").asText().equals("string") && !(schema.path("type").isArray() && schema.path("type").get(0).asText().equals("string"))) throw unsupported(name);
                    result.put("format", "binary");
                }
                case "properties" -> {
                    var properties = JSON.createObjectNode();
                    value.fields().forEachRemaining(property -> properties.set(property.getKey(), toOpenApi30(property.getValue(), response)));
                    result.set(name, properties);
                }
                case "items", "not" -> result.set(name, toOpenApi30(value, response));
                case "additionalProperties" -> result.set(name, value.isBoolean() ? value : toOpenApi30(value, response));
                case "allOf", "oneOf", "anyOf" -> {
                    var variants = JSON.createArrayNode();
                    value.forEach(child -> variants.add(toOpenApi30(child, response)));
                    result.set(name, variants);
                }
                default -> {
                    if (!SCALAR_KEYWORDS.contains(name)) throw unsupported(name);
                    if ((name.equals("exclusiveMinimum") || name.equals("exclusiveMaximum")) && !value.isBoolean()) throw unsupported(name);
                    result.set(name, value);
                }
            }
        });
        return result;
    }

    private static void convertType(JsonNode type, ObjectNode target) {
        if (!type.isArray()) {
            if (type.asText().equals("null")) throw unsupported("type: null");
            target.set("type", type);
            return;
        }
        var nonNull = new ArrayList<JsonNode>();
        type.forEach(value -> { if (!value.asText().equals("null")) nonNull.add(value); });
        if (type.size() != 2 || nonNull.size() != 1) throw unsupported("type union " + type);
        target.set("type", nonNull.getFirst());
        target.put("nullable", true);
    }

    private static IllegalArgumentException unsupported(String name) {
        return new IllegalArgumentException("Unsupported OpenAPI 3.1 construct: " + name);
    }

    private static Set<String> nullablePaths(JsonNode node, String path) {
        var result = new TreeSet<String>();
        if (node.isObject()) {
            if (node.path("nullable").asBoolean(false)) result.add(path);
            node.fields().forEachRemaining(entry -> result.addAll(nullablePaths(entry.getValue(), path + "/" + entry.getKey())));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) result.addAll(nullablePaths(node.get(i), path + "/" + i));
        }
        return result;
    }
}

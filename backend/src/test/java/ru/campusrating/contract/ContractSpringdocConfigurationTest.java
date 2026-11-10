package ru.campusrating.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.configuration.SpringDocSpecPropertiesConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.campusrating.ledger.LedgerPage;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContractSpringdocConfigurationTest {
    private ConfigurableApplicationContext context;
    private RestTestClient client;
    private JsonNode document;

    @BeforeAll
    void startSpringdoc() throws Exception {
        context = new SpringApplicationBuilder(WebConfiguration.class).properties(
                "server.port=0", "springdoc.api-docs.version=openapi_3_0",
                "springdoc.default-produces-media-type=application/json",
                "springdoc.model-converters.java-nullable-property-customizer.enabled=true").run();
        var port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        client = RestTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        document = new ObjectMapper().readTree(get("/v3/api-docs"));
    }

    @AfterAll
    void stopSpringdoc() {
        if (context != null) context.close();
    }

    @Test
    void jacksonWritesNullRatherThanOmittingRecordProperties() throws Exception {
        var body = new ObjectMapper().readTree(get("/contract-probe"));
        assertThat(body.has("nullableText")).isTrue();
        assertThat(body.path("nullableText").isNull()).isTrue();
        assertThat(body.has("nullableNested")).isTrue();
        assertThat(body.path("nullableNested").isNull()).isTrue();
    }

    @Test
    void jspecifyTypeUseControlsNullableWithoutChangingSharedSchemas() {
        var properties = responseSchema("/contract-probe").path("properties");
        assertThat(properties.path("nullableText").path("nullable").asBoolean()).isTrue();
        assertThat(properties.path("requiredText").path("nullable").asBoolean()).isFalse();
        assertThat(properties.path("nullableNested").path("nullable").asBoolean()).isTrue();
        var requiredNested = dereference(properties.path("requiredNested"));
        assertThat(requiredNested.path("nullable").asBoolean()).isFalse();
        assertThat(requiredNested.path("properties").path("title").path("nullable").asBoolean()).isFalse();
    }

    @Test
    void allRecordPropertiesIncludingNullableOnesAreRequired() {
        assertThat(required(responseSchema("/contract-probe")))
                .containsExactlyInAnyOrder("requiredText", "nullableText", "nullableNested", "requiredNested");
        var page = responseSchema("/contract-page");
        assertThat(required(page)).containsExactlyInAnyOrder("items", "nextCursor");
        assertThat(page.at("/properties/nextCursor/nullable").asBoolean()).isTrue();
        assertThat(page.at("/properties/items/nullable").asBoolean()).isFalse();
        assertThat(required(dereference(page.at("/properties/items/items"))))
                .containsExactlyInAnyOrder("requiredText", "nullableText", "nullableNested", "requiredNested");
        var generic = responseSchema("/contract-generic");
        assertThat(required(generic)).containsExactlyInAnyOrder("value", "nullableValue");
        assertThat(generic.at("/properties/nullableValue/nullable").asBoolean()).isTrue();
        assertThat(dereference(generic.at("/properties/value")).path("nullable").asBoolean()).isFalse();
    }

    private List<String> required(JsonNode schema) {
        return StreamSupport.stream(schema.path("required").spliterator(), false).map(JsonNode::asText).toList();
    }

    private JsonNode responseSchema(String path) {
        return dereference(document.path("paths").path(path).at("/get/responses/200/content/application~1json/schema"));
    }

    private JsonNode dereference(JsonNode schema) {
        return schema.has("$ref") ? document.at(schema.path("$ref").asText().substring(1)) : schema;
    }

    private String get(String path) {
        return client.get().uri(path).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(ContractSpringdocConfiguration.class)
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            SpringDocConfiguration.class, SpringDocConfigProperties.class,
            SpringDocSpecPropertiesConfiguration.class, SpringDocWebMvcConfiguration.class})
    static class WebConfiguration {
        @RestController
        class ProbeController {
            @GetMapping("/contract-probe")
            public NullabilityProbe probe() { return new NullabilityProbe("present", null, null, new Nested("present")); }

            @GetMapping("/contract-page")
            public LedgerPage<NullabilityProbe> page() { return new LedgerPage<>(List.of(probe()), null); }

            @GetMapping("/contract-generic")
            public GenericProbe<Nested> generic() { return new GenericProbe<>(new Nested("present"), null); }
        }
    }

    record NullabilityProbe(String requiredText, @Nullable String nullableText, @Nullable Nested nullableNested, Nested requiredNested) {}
    record Nested(String title) {}
    record GenericProbe<T>(T value, @Nullable T nullableValue) {}
}

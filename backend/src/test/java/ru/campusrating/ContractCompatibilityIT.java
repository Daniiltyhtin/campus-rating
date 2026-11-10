package ru.campusrating;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.client.RestTestClient;
import ru.campusrating.contract.ContractComparison;
import ru.campusrating.contract.ContractSpringdocConfiguration;
import org.springframework.context.annotation.Import;
import ru.campusrating.support.PostgresIntegrationTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "campus.mail.email-verification=off", "springdoc.api-docs.version=openapi_3_0",
        "springdoc.default-produces-media-type=application/json",
        "springdoc.model-converters.java-nullable-property-customizer.enabled=true"
})
@Import(ContractSpringdocConfiguration.class)
class ContractCompatibilityIT extends PostgresIntegrationTest {
    @LocalServerPort
    private int port;

    @Test
    void implementedOperationsMatchContract() throws Exception {
        var response = RestTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build()
                .get().uri("/v3/api-docs").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        var contract = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(contractPath()));
        ContractComparison.assertCompatible(contract, new ObjectMapper().readTree(response));
    }

    private static Path contractPath() {
        var root = Path.of("").toAbsolutePath();
        while (root != null) {
            var contract = root.resolve("docs/spec/api/openapi.yaml");
            if (Files.isRegularFile(contract)) return contract;
            root = root.getParent();
        }
        throw new IllegalStateException("Cannot find docs/spec/api/openapi.yaml from repository working directory");
    }
}

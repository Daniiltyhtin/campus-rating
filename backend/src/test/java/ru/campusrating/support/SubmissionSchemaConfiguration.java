package ru.campusrating.support;

import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.media.Schema;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import ru.campusrating.checkpoint.api.SubmissionUpload;

@TestConfiguration(proxyBeanMethods = false)
public class SubmissionSchemaConfiguration {
    // Swagger-аннотации не представляют boolean-схему false для запрещённой части multipart.
    @Bean
    ModelConverter forbiddenSubmissionPart() {
        return (type, context, chain) -> {
            if (type.getType() == SubmissionUpload.ForbiddenPart.class) {
                return new Schema<>().booleanSchemaValue(false);
            }
            return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        };
    }
}

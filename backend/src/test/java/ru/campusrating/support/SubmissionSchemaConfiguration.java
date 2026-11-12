package ru.campusrating.support;

import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Set;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import ru.campusrating.checkpoint.api.CheckpointAssignmentRow;
import ru.campusrating.checkpoint.api.SubmissionUpload;

@TestConfiguration(proxyBeanMethods = false)
public class SubmissionSchemaConfiguration {
    @Bean
    ModelConverter nullableSubmissionKind() {
        return (type, context, chain) -> {
            if (type.getType() == CheckpointAssignmentRow.NullableSubmissionKind.class) {
                return new Schema<>()
                        .anyOf(
                                List.of(
                                        new Schema<String>()
                                                .types(Set.of("string"))
                                                ._enum(List.of("LINK", "FILE")),
                                        new Schema<>().types(Set.of("null"))));
            }
            return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        };
    }

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

package ru.campusrating.contract;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.Iterator;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class ContractSpringdocConfiguration {
    @Bean
    ModelConverter requiredRecordProperties(ObjectMapperProvider mapper) {
        return new RequiredRecordProperties(mapper);
    }

    private record RequiredRecordProperties(ObjectMapperProvider mapper) implements ModelConverter {
        @Override
        public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
            if (!chain.hasNext()) return null;
            var resolved = chain.next().resolve(type, context, chain);
            if (resolved == null || !mapper.jsonMapper().constructType(type.getType()).getRawClass().isRecord()) return resolved;
            var target = resolved.get$ref() == null ? resolved : context.getDefinedModels()
                    .get(resolved.get$ref().substring(Components.COMPONENTS_SCHEMAS_REF.length()));
            if (target != null && target.getProperties() != null) {
                // В контракте отсутствующее значение — null, а не пропуск поля: Jackson пишет каждое свойство.
                target.setRequired(new ArrayList<>(target.getProperties().keySet()));
            }
            return resolved;
        }
    }
}

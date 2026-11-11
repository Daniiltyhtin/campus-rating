package ru.campusrating.support;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.campusrating.activity.api.ActivityController.Activity;

@Configuration(proxyBeanMethods = false)
public class ActivitySchemaConfiguration {
    @Bean
    ModelConverter activitySchemaConverter() {
        return new ModelConverter() {
            @Override
            public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
                if (!chain.hasNext()) return null;
                Schema<?> resolved = chain.next().resolve(type, context, chain);
                if (resolved == null) return null;
                Schema<?> model = resolved.get$ref() == null ? resolved
                        : context.getDefinedModels().get(resolved.get$ref().substring(resolved.get$ref().lastIndexOf('/') + 1));
                if (model != null && type.getType() == Activity.class) {
                    model.addProperty("myAward", new ComposedSchema()
                            .addAnyOfItem(new Schema<>().$ref("#/components/schemas/MyAward"))
                            .addAnyOfItem(new Schema<>().types(Set.of("null"))));
                    context.getDefinedModels().get("MyAward")
                            .addProperty("level", new IntegerSchema()._enum(List.of(25, 50, 75, 100)));
                }
                return resolved;
            }
        };
    }
}

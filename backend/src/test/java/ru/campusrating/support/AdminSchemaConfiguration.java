package ru.campusrating.support;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Iterator;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AdminSchemaConfiguration {
    private static final Set<String> DIRECTORY_TYPES = Set.of("FacultyInfo", "DepartmentInfo", "AdminStudyGroup");

    @Bean
    ModelConverter adminSchemaConverter() {
        return new ModelConverter() {
            @Override
            public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
                if (!chain.hasNext()) return null;
                Schema<?> resolved = chain.next().resolve(type, context, chain);
                if (resolved == null) return null;
                String name = resolved.get$ref() == null ? resolved.getName()
                        : resolved.get$ref().substring(resolved.get$ref().lastIndexOf('/') + 1);
                Schema<?> model = resolved.get$ref() == null ? resolved : context.getDefinedModels().get(name);
                if (model == null || name == null) return resolved;
                if (DIRECTORY_TYPES.contains(name)) {
                    model.getProperties().get("code").minLength(1).maxLength(64).pattern("\\S");
                    model.getProperties().get("name").minLength(1).maxLength(255).pattern("\\S");
                }
                if (name.equals("AdminTeacher")) model.getProperties().get("fullName").minLength(1);
                return resolved;
            }
        };
    }
}

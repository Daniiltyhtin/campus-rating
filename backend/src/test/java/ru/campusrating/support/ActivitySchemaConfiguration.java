package ru.campusrating.support;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.campusrating.activity.api.ActivityController.Activity;
import ru.campusrating.activity.api.ActivityController.MyAward;
import ru.campusrating.activity.api.BudgetController.TeacherAward;
import ru.campusrating.activity.api.ParticipationController.AwardLine;
import ru.campusrating.activity.api.ParticipationController.ConfirmedParticipation;
import ru.campusrating.activity.api.ParticipationController.Participant;
import ru.campusrating.activity.api.ParticipationController.ParticipationItem;

@Configuration(proxyBeanMethods = false)
public class ActivitySchemaConfiguration {
    private static final Set<String> LEVELS = Set.of(MyAward.class.getSimpleName(), ConfirmedParticipation.class.getSimpleName(),
            ParticipationItem.class.getSimpleName(), AwardLine.class.getSimpleName(), TeacherAward.class.getSimpleName());
    private static final Map<String, Map.Entry<String, String>> NULLABLE_REFS = Map.of(
            Activity.class.getSimpleName(), Map.entry("myAward", MyAward.class.getSimpleName()),
            Participant.class.getSimpleName(), Map.entry("confirmed", ConfirmedParticipation.class.getSimpleName()));

    @Bean
    ModelConverter activitySchemaConverter() {
        return new ModelConverter() {
            @Override
            public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
                if (!chain.hasNext()) {
                    return null;
                }
                Schema<?> resolved = chain.next().resolve(type, context, chain);
                if (resolved == null) {
                    return null;
                }
                String name = resolved.get$ref() == null ? resolved.getName()
                        : resolved.get$ref().substring(resolved.get$ref().lastIndexOf('/') + 1);
                Schema<?> model = resolved.get$ref() == null ? resolved : context.getDefinedModels().get(name);
                if (model == null || name == null) {
                    return resolved;
                }
                if (LEVELS.contains(name)) {
                    model.addProperty("level", new IntegerSchema()._enum(List.of(25, 50, 75, 100)));
                }
                var nullable = NULLABLE_REFS.get(name);
                if (nullable != null) {
                    model.addProperty(nullable.getKey(), new ComposedSchema()
                            .addAnyOfItem(new Schema<>().$ref("#/components/schemas/" + nullable.getValue()))
                            .addAnyOfItem(new Schema<>().types(Set.of("null"))));
                }
                return resolved;
            }
        };
    }
}

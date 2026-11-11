package ru.campusrating.checkpoint;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import ru.campusrating.checkpoint.api.CheckpointCreate;
import ru.campusrating.checkpoint.api.CheckpointUpdate;

import static org.assertj.core.api.Assertions.*;

class CheckpointSchemaTest {
    @Test
    void schemasPreserveContractTypesAndRequiredFields() {
        var schemas = ModelConverters.getInstance(true).readAll(CheckpointCreate.class);
        var create = schemas.get("CheckpointCreate");
        assertThat(create.getRequired())
                .containsExactlyInAnyOrder("title", "description", "groupIds", "deadline", "cost");
        var cost = (Schema<?>) create.getProperties().get("cost");
        assertThat(cost.getTypes()).containsExactly("integer");
        assertThat(cost.getFormat()).isEqualTo("int32");
        assertThat(cost.getMinimum()).isEqualByComparingTo("1");
        assertThat(cost.getMaximum()).isEqualByComparingTo("1000");
        var groups = (Schema<?>) create.getProperties().get("groupIds");
        assertThat(groups.getUniqueItems()).isTrue();
        assertThat(groups.getMinItems()).isEqualTo(1);
        var update =
                ModelConverters.getInstance(true)
                        .readAll(CheckpointUpdate.class)
                        .get("CheckpointUpdate");
        assertThat(update.getAdditionalProperties()).isEqualTo(false);
        assertThat(update.getMinProperties()).isEqualTo(1);
        assertThat(update.getRequired()).isNullOrEmpty();
        assertThat(update.getProperties()).containsOnlyKeys("title", "description");
    }
}

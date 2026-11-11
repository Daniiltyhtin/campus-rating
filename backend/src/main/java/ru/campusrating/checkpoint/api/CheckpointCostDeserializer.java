package ru.campusrating.checkpoint.api;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import ru.campusrating.checkpoint.internal.CheckpointValidation;

public final class CheckpointCostDeserializer extends ValueDeserializer<Integer> {
    @Override public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            throw CheckpointValidation.invalid("cost", "Стоимость должна быть целым числом");
        }
        try {
            return parser.getBigIntegerValue().intValueExact();
        } catch (ArithmeticException exception) {
            throw CheckpointValidation.invalid("cost", "Стоимость должна быть от 1 до 1000");
        }
    }
}

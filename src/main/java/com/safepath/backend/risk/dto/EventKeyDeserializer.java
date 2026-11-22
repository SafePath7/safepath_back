package com.safepath.backend.risk.dto;

import java.util.UUID;
import java.util.regex.Pattern;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public class EventKeyDeserializer extends ValueDeserializer<UUID> {
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    @Override
    public UUID deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.hasToken(JsonToken.VALUE_STRING)) {
            String text = parser.getString();
            if (UUID_TEXT.matcher(text).matches()) {
                return UUID.fromString(text);
            }
        }
        return invalid(context);
    }

    @Override
    public UUID getNullValue(DeserializationContext context) {
        return invalid(context);
    }

    @Override
    public UUID getAbsentValue(DeserializationContext context) {
        return null;
    }

    private UUID invalid(DeserializationContext context) {
        return context.reportInputMismatch(UUID.class, "eventKey must be a hyphenated UUID string");
    }
}

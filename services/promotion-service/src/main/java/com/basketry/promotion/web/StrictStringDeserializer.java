package com.basketry.promotion.web;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads a String only from a JSON string, a copy of order-service's: a number
 * or a boolean is a 400. Jackson converts them to text even with scalar
 * coercion turned off. An explicit null still arrives as null, for the
 * request's own rule.
 */
class StrictStringDeserializer extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            return parser.getString();
        }
        return (String) context.handleUnexpectedToken(String.class, parser);
    }
}

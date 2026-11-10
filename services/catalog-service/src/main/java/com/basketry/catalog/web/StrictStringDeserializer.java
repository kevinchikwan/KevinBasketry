package com.basketry.catalog.web;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads a String only from a JSON string. A number such as {@code 5} or a
 * boolean is a 400: Jackson converts them to text even with scalar coercion
 * turned off, which a test showed. An explicit null still arrives as null, for
 * the caller's own rule. Numbers and booleans need no such class: the global
 * setting allow-coercion-of-scalars: false already refuses {@code "2.50"} for a
 * price and {@code "false"} or {@code 0} for a boolean (tested).
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

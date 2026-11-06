package com.basketry.merchant.merchant;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads a Boolean only from a JSON true or false. A string such as
 * {@code "false"} or a number such as {@code 0} is a 400, where Jackson would
 * otherwise convert it. An explicit null still arrives as null, for the
 * caller's own rule.
 */
class StrictBooleanDeserializer extends ValueDeserializer<Boolean> {

    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE) {
            return parser.getBooleanValue();
        }
        return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
    }
}

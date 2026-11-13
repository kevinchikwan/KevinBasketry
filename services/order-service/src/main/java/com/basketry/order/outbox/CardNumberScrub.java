package com.basketry.order.outbox;

import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A payload with its card number replaced by null (DESIGN.md Section 2,
 * order-service, the card number): what a charge request's row keeps once it is
 * SENT or CANCELLED. A replay of the stored payload by hand is then
 * dead-lettered by payment-service (cardNumber is required), never charged. A
 * masked number would be worse: payment-service approves any number not in its
 * table. Every other field, and their order, stays as it was; a payload without
 * a card number comes back unchanged.
 */
@Component
class CardNumberScrub {

    static final String FIELD = "cardNumber";

    private final JsonMapper json;
    /** Decimals read as BigDecimal: the default would turn an amount of 2.50 into 2.5. */
    private final ObjectReader reader;

    CardNumberScrub(JsonMapper json) {
        this.json = json;
        this.reader = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    String scrubbed(String payload) {
        JsonNode tree = reader.readTree(payload);
        if (!(tree instanceof ObjectNode object) || !object.has(FIELD)) {
            return payload;
        }
        object.putNull(FIELD);
        return json.writeValueAsString(object);
    }
}

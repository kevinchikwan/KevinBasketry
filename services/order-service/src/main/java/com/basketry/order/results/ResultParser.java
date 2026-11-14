package com.basketry.order.results;

import com.basketry.order.saga.SagaResult;
import com.basketry.order.saga.SagaResult.Charged;
import com.basketry.order.saga.SagaResult.Declined;
import com.basketry.order.saga.SagaResult.Refunded;
import com.basketry.order.saga.SagaResult.Released;
import com.basketry.order.saga.SagaResult.ReservationFailed;
import com.basketry.order.saga.SagaResult.Reserved;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a result record's value into a {@link SagaResult} (DESIGN.md Section 5,
 * order-service results), checked by hand on the JSON tree as catalog-service's
 * consumer does:
 *
 * <ul>
 *   <li>{@code schemaVersion} first, and only 1.</li>
 *   <li>Ids are positive whole JSON numbers that fit 64 bits; {@code 7.0} and
 *       {@code "7"} are refused.</li>
 *   <li>{@code transactionRef} is a string in payment-service's format
 *       ({@code TXN-} and 32 lowercase hex digits); {@code reasonCode} a string
 *       of 1 to 40 characters.</li>
 *   <li>{@code eventId} and {@code occurredAt} are optional strings, not used.</li>
 *   <li>A repeated field and anything after the object are refused; unknown
 *       fields are ignored, as identity-service's and catalog-service's
 *       consumers do.</li>
 * </ul>
 *
 * <p>No exception message quotes a value or a Jackson message.
 */
@Component
public class ResultParser {

    static final int SCHEMA_VERSION = 1;
    private static final Pattern TRANSACTION_REF = Pattern.compile("TXN-[0-9a-f]{32}");
    private static final int MAX_REASON_CODE = 40;

    private final ObjectReader reader;

    public ResultParser(JsonMapper json) {
        this.reader = json.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** @throws InvalidResultException if the value can never be a result of {@code topic} */
    public SagaResult parse(String topic, String value) {
        JsonNode tree = readObject(value);
        long orderId = id(tree, "orderId");
        return switch (topic) {
            case ResultTopics.CHARGED -> new Charged(orderId, id(tree, "merchantId"), transactionRef(tree));
            case ResultTopics.DECLINED -> new Declined(orderId, id(tree, "merchantId"), reasonCode(tree));
            case ResultTopics.REFUNDED -> new Refunded(orderId, id(tree, "merchantId"));
            case ResultTopics.RESERVED -> new Reserved(orderId, id(tree, "productId"));
            case ResultTopics.RESERVATION_FAILED -> new ReservationFailed(orderId, id(tree, "productId"),
                    reasonCode(tree));
            case ResultTopics.RELEASED -> new Released(orderId, id(tree, "productId"));
            default -> throw new IllegalArgumentException("Not a result topic: " + topic);
        };
    }

    private JsonNode readObject(String value) {
        if (value == null) {
            throw new InvalidResultException("The record has no value");
        }
        JsonNode tree;
        try {
            tree = reader.readTree(value);
        } catch (JacksonException unreadable) {
            throw new InvalidResultException("The value is not valid JSON" + where(unreadable));
        }
        if (tree == null || !tree.isObject()) {
            throw new InvalidResultException("The value is not a JSON object");
        }
        JsonNode schemaVersion = tree.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isIntegralNumber() || !schemaVersion.canConvertToInt()
                || schemaVersion.intValue() != SCHEMA_VERSION) {
            throw new InvalidResultException("schemaVersion is missing or not " + SCHEMA_VERSION);
        }
        optionalString(tree, "eventId");
        optionalString(tree, "occurredAt");
        return tree;
    }

    /** Only the position: Jackson's own message can quote the input. */
    private static String where(JacksonException unreadable) {
        TokenStreamLocation location = unreadable.getLocation();
        if (location == null || location.getLineNr() < 0) {
            return "";
        }
        return " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
    }

    private static long id(JsonNode tree, String name) {
        JsonNode node = required(tree, name);
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() <= 0) {
            throw new InvalidResultException(name + " must be a positive whole JSON number that fits 64 bits");
        }
        return node.longValue();
    }

    private static String transactionRef(JsonNode tree) {
        JsonNode node = required(tree, "transactionRef");
        if (!node.isString() || !TRANSACTION_REF.matcher(node.stringValue()).matches()) {
            throw new InvalidResultException("transactionRef must be TXN- and 32 lowercase hex digits");
        }
        return node.stringValue();
    }

    private static String reasonCode(JsonNode tree) {
        JsonNode node = required(tree, "reasonCode");
        if (!node.isString() || node.stringValue().isEmpty() || node.stringValue().length() > MAX_REASON_CODE) {
            throw new InvalidResultException("reasonCode must be a string of 1 to " + MAX_REASON_CODE + " characters");
        }
        return node.stringValue();
    }

    private static void optionalString(JsonNode tree, String name) {
        JsonNode node = tree.get(name);
        if (node != null && !node.isString()) {
            throw new InvalidResultException(name + " must be a JSON string if present");
        }
    }

    private static JsonNode required(JsonNode tree, String name) {
        JsonNode node = tree.get(name);
        if (node == null || node.isNull()) {
            throw new InvalidResultException(name + " is required");
        }
        return node;
    }
}

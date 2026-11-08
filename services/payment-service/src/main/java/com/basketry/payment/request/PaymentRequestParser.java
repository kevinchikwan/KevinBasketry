package com.basketry.payment.request;

import com.basketry.payment.payment.ChargeCommand;
import com.basketry.payment.payment.InvalidPaymentRequestException;
import com.basketry.payment.payment.RefundCommand;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a request record's value into a command (DESIGN.md Section 5,
 * payment-service requests). Strict, and every rule is checked here by hand,
 * not left to Jackson's binding:
 *
 * <ul>
 *   <li>{@code schemaVersion} first, and only 1, because another version may
 *       not have the fields below.</li>
 *   <li>Unknown fields are refused, unlike identity-service's consumer: a
 *       payment must not silently ignore a field it does not understand. A new
 *       field means a new schemaVersion.</li>
 *   <li>Each field must have its JSON type: ids are whole numbers that fit a
 *       long, {@code amount} is a number (read as a decimal, never a double),
 *       {@code cardNumber} and {@code transactionRef} are strings. No
 *       conversion, so {@code "cardNumber":4000…} and {@code "amount":"10"} are
 *       refused.</li>
 *   <li>A repeated field and anything after the object are refused.</li>
 * </ul>
 *
 * <p>No exception message quotes a value, a field name from the record or a
 * Jackson message (which can quote the card number), and none carries a
 * Jackson exception as its cause. The messages go into dead-letter headers and
 * log lines.
 */
@Component
public class PaymentRequestParser {

    static final int SCHEMA_VERSION = 1;

    private static final Set<String> CHARGE_FIELDS =
            Set.of("schemaVersion", "eventId", "orderId", "merchantId", "amount", "cardNumber", "occurredAt");
    private static final Set<String> REFUND_FIELDS =
            Set.of("schemaVersion", "eventId", "orderId", "merchantId", "transactionRef", "occurredAt");

    private final ObjectReader reader;

    public PaymentRequestParser(JsonMapper json) {
        this.reader = json.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** @throws InvalidPaymentRequestException if the value can never be a charge */
    public ChargeCommand parseCharge(String value) {
        JsonNode tree = readObject(value, CHARGE_FIELDS);
        return new ChargeCommand(id(tree, "orderId"), id(tree, "merchantId"), amount(tree),
                string(tree, "cardNumber"));
    }

    /** @throws InvalidPaymentRequestException if the value can never be a refund */
    public RefundCommand parseRefund(String value) {
        JsonNode tree = readObject(value, REFUND_FIELDS);
        return new RefundCommand(id(tree, "orderId"), id(tree, "merchantId"), string(tree, "transactionRef"));
    }

    private JsonNode readObject(String value, Set<String> allowed) {
        if (value == null) {
            throw new InvalidPaymentRequestException("The record has no value");
        }
        JsonNode tree;
        try {
            tree = reader.readTree(value);
        } catch (JacksonException unreadable) {
            throw new InvalidPaymentRequestException("The value is not valid JSON" + where(unreadable));
        }
        if (tree == null || !tree.isObject()) {
            throw new InvalidPaymentRequestException("The value is not a JSON object");
        }
        JsonNode schemaVersion = tree.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isInt() || schemaVersion.intValue() != SCHEMA_VERSION) {
            throw new InvalidPaymentRequestException("schemaVersion is missing or not " + SCHEMA_VERSION);
        }
        for (String name : tree.propertyNames()) {
            if (!allowed.contains(name)) {
                throw new InvalidPaymentRequestException(
                        "The value has a field that schemaVersion " + SCHEMA_VERSION + " does not have");
            }
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
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new InvalidPaymentRequestException(name + " must be a whole JSON number that fits 64 bits");
        }
        return node.longValue();
    }

    private static BigDecimal amount(JsonNode tree) {
        JsonNode node = required(tree, "amount");
        if (!node.isNumber()) {
            throw new InvalidPaymentRequestException("amount must be a JSON number");
        }
        return node.decimalValue();
    }

    private static String string(JsonNode tree, String name) {
        JsonNode node = required(tree, name);
        if (!node.isString()) {
            throw new InvalidPaymentRequestException(name + " must be a JSON string");
        }
        return node.stringValue();
    }

    private static void optionalString(JsonNode tree, String name) {
        JsonNode node = tree.get(name);
        if (node != null && !node.isString()) {
            throw new InvalidPaymentRequestException(name + " must be a JSON string if present");
        }
    }

    private static JsonNode required(JsonNode tree, String name) {
        JsonNode node = tree.get(name);
        if (node == null || node.isNull()) {
            throw new InvalidPaymentRequestException(name + " is required");
        }
        return node;
    }
}

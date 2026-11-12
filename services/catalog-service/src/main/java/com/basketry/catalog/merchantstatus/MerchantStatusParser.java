package com.basketry.catalog.merchantstatus;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a merchant.status.changed value into an event (DESIGN.md Section 5,
 * the catalog-service consumer). The rules are identity-service's, checked by
 * hand on the JSON tree as inventory-service's parser does:
 *
 * <ul>
 *   <li>{@code schemaVersion} first, and only 1: another version may not have
 *       the fields below.</li>
 *   <li>{@code merchantId} and {@code version}: positive whole JSON numbers that
 *       fit a long. {@code active}: a JSON boolean. No conversion between
 *       types: {@code 7.0}, {@code "7"}, {@code "false"} and {@code 0} are
 *       refused.</li>
 *   <li>Unknown fields are ignored, as in identity-service: the event carries
 *       fields no consumer needs (merchantCode, eventId, occurredAt).</li>
 *   <li>Unlike identity-service, a repeated field is refused (otherwise the
 *       last one would win, and {@code "active":false,"active":true} would undo
 *       a deactivation), and so is anything after the object.</li>
 * </ul>
 *
 * <p>No exception message quotes a value or a Jackson message: the messages go
 * into dead-letter headers and log lines.
 */
@Component
public class MerchantStatusParser {

    static final int SCHEMA_VERSION = 1;

    private final ObjectReader reader;

    public MerchantStatusParser(JsonMapper json) {
        // Scoped to this reader: the service's mapper, and so its REST bodies, are unchanged.
        this.reader = json.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** @throws InvalidMerchantStatusEventException if the value can never be applied */
    public MerchantStatusEvent parse(String value) {
        if (value == null) {
            throw new InvalidMerchantStatusEventException("The record has no value");
        }
        JsonNode tree;
        try {
            tree = reader.readTree(value);
        } catch (JacksonException unreadable) {
            throw new InvalidMerchantStatusEventException("The value is not valid JSON" + where(unreadable));
        }
        if (tree == null || !tree.isObject()) {
            throw new InvalidMerchantStatusEventException("The value is not a JSON object");
        }
        JsonNode schemaVersion = tree.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isIntegralNumber() || !schemaVersion.canConvertToInt()
                || schemaVersion.intValue() != SCHEMA_VERSION) {
            throw new InvalidMerchantStatusEventException("schemaVersion is missing or not " + SCHEMA_VERSION);
        }
        long merchantId = positiveId(tree, "merchantId");
        JsonNode active = required(tree, "active");
        if (!active.isBoolean()) {
            throw new InvalidMerchantStatusEventException("active must be a JSON boolean");
        }
        long version = positiveId(tree, "version");
        return new MerchantStatusEvent(merchantId, active.booleanValue(), version);
    }

    /** Only the position: Jackson's own message can quote the input. */
    private static String where(JacksonException unreadable) {
        TokenStreamLocation location = unreadable.getLocation();
        if (location == null || location.getLineNr() < 0) {
            return "";
        }
        return " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
    }

    private static long positiveId(JsonNode tree, String name) {
        JsonNode node = required(tree, name);
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new InvalidMerchantStatusEventException(name + " must be a whole JSON number that fits 64 bits");
        }
        if (node.longValue() <= 0) {
            throw new InvalidMerchantStatusEventException(name + " must be positive");
        }
        return node.longValue();
    }

    private static JsonNode required(JsonNode tree, String name) {
        JsonNode node = tree.get(name);
        if (node == null || node.isNull()) {
            throw new InvalidMerchantStatusEventException(name + " is required");
        }
        return node;
    }
}

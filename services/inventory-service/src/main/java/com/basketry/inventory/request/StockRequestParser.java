package com.basketry.inventory.request;

import com.basketry.inventory.stock.InvalidStockRequestException;
import com.basketry.inventory.stock.ReleaseCommand;
import com.basketry.inventory.stock.ReserveCommand;
import java.math.BigInteger;
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
 * Turns a reserve or release record's value into a command (DESIGN.md Section
 * 5, inventory-service requests). Strict, as payment-service's parser, and
 * every rule is checked here by hand, not left to Jackson's binding:
 *
 * <ul>
 *   <li>{@code schemaVersion} first, and only 1.</li>
 *   <li>Unknown fields are refused: a new field means a new schemaVersion.</li>
 *   <li>Ids are whole JSON numbers that fit a long; the commands then require
 *       them to be positive.</li>
 *   <li>{@code quantity} is a whole JSON number of any size, read as a big
 *       integer so it can never overflow. Zero or less is refused. Anything
 *       above {@link Integer#MAX_VALUE} becomes {@link Integer#MAX_VALUE}: a
 *       reserve above the limit is answered QUANTITY_OVER_LIMIT whatever its
 *       size, and a release gives back the stored quantity, never the
 *       request's. So a stored FAILED row or tombstone holds "at least this
 *       much", and its quantity is never used in arithmetic.</li>
 *   <li>No conversion between types: {@code 7.0}, {@code 1e3} and {@code "7"}
 *       are refused. A repeated field and anything after the object are
 *       refused.</li>
 * </ul>
 *
 * <p>No exception message quotes a value or a Jackson message, and none carries
 * a Jackson exception as its cause: the messages go into dead-letter headers
 * and log lines.
 */
@Component
public class StockRequestParser {

    static final int SCHEMA_VERSION = 1;

    private static final Set<String> FIELDS =
            Set.of("schemaVersion", "eventId", "orderId", "productId", "quantity", "occurredAt");
    private static final BigInteger MAX_QUANTITY = BigInteger.valueOf(Integer.MAX_VALUE);

    private final ObjectReader reader;

    public StockRequestParser(JsonMapper json) {
        this.reader = json.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** @throws InvalidStockRequestException if the value can never be a reserve request */
    public ReserveCommand parseReserve(String value) {
        JsonNode tree = readObject(value);
        return new ReserveCommand(id(tree, "orderId"), id(tree, "productId"), quantity(tree));
    }

    /** @throws InvalidStockRequestException if the value can never be a release request */
    public ReleaseCommand parseRelease(String value) {
        JsonNode tree = readObject(value);
        return new ReleaseCommand(id(tree, "orderId"), id(tree, "productId"), quantity(tree));
    }

    private JsonNode readObject(String value) {
        if (value == null) {
            throw new InvalidStockRequestException("The record has no value");
        }
        JsonNode tree;
        try {
            tree = reader.readTree(value);
        } catch (JacksonException unreadable) {
            throw new InvalidStockRequestException("The value is not valid JSON" + where(unreadable));
        }
        if (tree == null || !tree.isObject()) {
            throw new InvalidStockRequestException("The value is not a JSON object");
        }
        JsonNode schemaVersion = tree.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isIntegralNumber() || !schemaVersion.canConvertToInt()
                || schemaVersion.intValue() != SCHEMA_VERSION) {
            throw new InvalidStockRequestException("schemaVersion is missing or not " + SCHEMA_VERSION);
        }
        for (String name : tree.propertyNames()) {
            if (!FIELDS.contains(name)) {
                throw new InvalidStockRequestException(
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
            throw new InvalidStockRequestException(name + " must be a whole JSON number that fits 64 bits");
        }
        return node.longValue();
    }

    /** Never overflows: a quantity of any size above the int range becomes Integer.MAX_VALUE. */
    private static int quantity(JsonNode tree) {
        JsonNode node = required(tree, "quantity");
        if (!node.isIntegralNumber()) {
            throw new InvalidStockRequestException("quantity must be a whole JSON number");
        }
        BigInteger quantity = node.bigIntegerValue();
        if (quantity.signum() <= 0) {
            throw new InvalidStockRequestException("quantity must be positive");
        }
        return quantity.min(MAX_QUANTITY).intValueExact();
    }

    private static void optionalString(JsonNode tree, String name) {
        JsonNode node = tree.get(name);
        if (node != null && !node.isString()) {
            throw new InvalidStockRequestException(name + " must be a JSON string if present");
        }
    }

    private static JsonNode required(JsonNode tree, String name) {
        JsonNode node = tree.get(name);
        if (node == null || node.isNull()) {
            throw new InvalidStockRequestException(name + " is required");
        }
        return node;
    }
}

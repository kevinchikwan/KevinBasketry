package com.basketry.promotion.usage;

import com.basketry.promotion.coupon.CouponCode;
import com.basketry.promotion.coupon.InvalidCouponException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
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
 * Turns an order.succeeded record's value into an {@link OrderSucceeded}
 * (DESIGN.md Section 5, promotion-service), checked by hand on the JSON tree as
 * order-service's result parser does:
 *
 * <ul>
 *   <li>No value (a tombstone) and bytes that are not UTF-8 are refused: the
 *       bytes are decoded here, strictly, never repaired with replacement
 *       characters.</li>
 *   <li>{@code schemaVersion} first, and only 1.</li>
 *   <li>{@code eventId} a UUID string (8-4-4-4-12 hex digits);
 *       {@code orderId} a positive whole JSON number that fits 64 bits;
 *       {@code occurredAt} an ISO-8601 instant string.</li>
 *   <li>{@code couponCode} present: JSON null, or a code already normalised
 *       (order-service normalises it before the fingerprint).</li>
 *   <li>{@code merchants} a non-empty array of objects, each with a
 *       {@code merchantId} and a {@code discount} (a JSON number, 0 or more, at
 *       most 2 decimal places). Checked, not kept: the count does not depend on
 *       the discount.</li>
 *   <li>A repeated field and anything after the object are refused; unknown
 *       fields are ignored (platform-service's fields will grow the event).</li>
 * </ul>
 *
 * <p>No exception message quotes a value or a Jackson message.
 */
@Component
public class OrderSucceededParser {

    static final int SCHEMA_VERSION = 1;
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int MAX_DISCOUNT_SCALE = 2;

    private final ObjectReader reader;

    public OrderSucceededParser(JsonMapper json) {
        this.reader = json.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** @throws InvalidOrderSucceededException if the value can never be counted */
    public OrderSucceeded parse(byte[] value) {
        JsonNode tree = readObject(value);
        UUID eventId = eventId(tree);
        long orderId = positiveLong(tree.get("orderId"), "orderId");
        CouponCode couponCode = couponCode(tree);
        Instant occurredAt = occurredAt(tree);
        merchants(tree);
        return new OrderSucceeded(eventId, orderId, couponCode, occurredAt);
    }

    private JsonNode readObject(byte[] value) {
        if (value == null) {
            throw new InvalidOrderSucceededException("The record has no value");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            throw new InvalidOrderSucceededException("The value is not valid UTF-8");
        }
        JsonNode tree;
        try {
            tree = reader.readTree(text);
        } catch (JacksonException unreadable) {
            throw new InvalidOrderSucceededException("The value is not valid JSON" + where(unreadable));
        }
        if (tree == null || !tree.isObject()) {
            throw new InvalidOrderSucceededException("The value is not a JSON object");
        }
        JsonNode schemaVersion = tree.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isIntegralNumber() || !schemaVersion.canConvertToInt()
                || schemaVersion.intValue() != SCHEMA_VERSION) {
            throw new InvalidOrderSucceededException("schemaVersion is missing or not " + SCHEMA_VERSION);
        }
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

    private static UUID eventId(JsonNode tree) {
        JsonNode node = tree.get("eventId");
        if (node == null || !node.isString() || !UUID_TEXT.matcher(node.stringValue()).matches()) {
            throw new InvalidOrderSucceededException("eventId must be a UUID string");
        }
        return UUID.fromString(node.stringValue());
    }

    private static long positiveLong(JsonNode node, String name) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() <= 0) {
            throw new InvalidOrderSucceededException(name + " must be a positive whole JSON number that fits 64 bits");
        }
        return node.longValue();
    }

    private static CouponCode couponCode(JsonNode tree) {
        JsonNode node = tree.get("couponCode");
        if (node == null) {
            throw new InvalidOrderSucceededException("couponCode is required (null for no coupon)");
        }
        if (node.isNull()) {
            return null;
        }
        try {
            if (node.isString()) {
                // The constructor takes only a normalised code; normalise() would repair one.
                return new CouponCode(node.stringValue());
            }
        } catch (InvalidCouponException notNormalised) {
            // Falls through to our own message, with no cause.
        }
        throw new InvalidOrderSucceededException("couponCode must be null or a normalised coupon code");
    }

    private static Instant occurredAt(JsonNode tree) {
        JsonNode node = tree.get("occurredAt");
        if (node != null && node.isString()) {
            try {
                return Instant.parse(node.stringValue());
            } catch (DateTimeParseException notAnInstant) {
                // Falls through to our own message, with no cause.
            }
        }
        throw new InvalidOrderSucceededException("occurredAt must be an ISO-8601 instant string");
    }

    private static void merchants(JsonNode tree) {
        JsonNode merchants = tree.get("merchants");
        if (merchants == null || !merchants.isArray() || merchants.isEmpty()) {
            throw new InvalidOrderSucceededException("merchants must be a non-empty array of objects");
        }
        for (int i = 0; i < merchants.size(); i++) {
            JsonNode merchant = merchants.get(i);
            if (!merchant.isObject()) {
                throw new InvalidOrderSucceededException("merchants must be a non-empty array of objects");
            }
            String at = "merchants[" + i + "].";
            positiveLong(merchant.get("merchantId"), at + "merchantId");
            JsonNode discount = merchant.get("discount");
            BigDecimal value = discount != null && discount.isNumber() ? discount.decimalValue() : null;
            if (value == null || value.signum() < 0 || value.scale() > MAX_DISCOUNT_SCALE) {
                throw new InvalidOrderSucceededException(at + "discount must be a JSON number, 0 or more, with at most "
                        + MAX_DISCOUNT_SCALE + " decimal places");
            }
        }
    }
}

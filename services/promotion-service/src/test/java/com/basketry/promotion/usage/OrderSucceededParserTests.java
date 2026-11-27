package com.basketry.promotion.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.promotion.coupon.CouponCode;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * order.succeeded as promotion-service reads it (DESIGN.md Section 5,
 * promotion-service). The accepted event is order-service's own golden file,
 * read from order-service's module, so the two services cannot drift apart
 * unseen; every refusal is that file with one change.
 */
class OrderSucceededParserTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final OrderSucceededParser PARSER = new OrderSucceededParser(JSON);

    /** Letters and digits only; no refusal message may quote it. */
    static final String SENTINEL = "Zq7Pw9LeakSent1nel";

    /** services/promotion-service/../order-service/src/test/resources/contracts, from where this class was compiled to. */
    static Path goldenFile() throws URISyntaxException {
        Path classes = Path.of(OrderSucceededParserTests.class.getProtectionDomain().getCodeSource().getLocation()
                .toURI());
        for (Path dir = classes; dir != null; dir = dir.getParent()) {
            if (dir.getFileName() != null && dir.getFileName().toString().equals("promotion-service")
                    && Files.isRegularFile(dir.resolve("build.gradle.kts"))) {
                return dir.resolve("../order-service/src/test/resources/contracts/order-succeeded.json").normalize();
            }
        }
        throw new IllegalStateException("No promotion-service module directory above " + classes);
    }

    static String golden() {
        try {
            return Files.readString(goldenFile(), StandardCharsets.UTF_8).strip();
        } catch (Exception unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    /** The golden event with one change. */
    static String goldenWith(Consumer<ObjectNode> change) {
        ObjectNode event = (ObjectNode) JSON.readTree(golden());
        change.accept(event);
        return JSON.writeValueAsString(event);
    }

    /** The golden event as an order that carried a coupon, as order-service will send it from A9. */
    static String withCoupon(String code) {
        return goldenWith(event -> {
            event.put("couponCode", code);
            ObjectNode merchant = (ObjectNode) event.get("merchants").get(0);
            merchant.put("discount", new java.math.BigDecimal("2.55"));
            merchant.put("amount", new java.math.BigDecimal("22.95"));
        });
    }

    private static OrderSucceeded parse(String value) {
        return PARSER.parse(value.getBytes(StandardCharsets.UTF_8));
    }

    // ---- accepted ----------------------------------------------------------------

    /**
     * order-service's golden file, as it is (no coupon). Mutations: the parser
     * reads "orderID" (the golden file's orderId is refused as missing); it
     * requires "discountAmount" in a merchant (the golden file has "discount").
     */
    @Test
    void orderServicesGoldenEventIsRead() {
        OrderSucceeded event = parse(golden());

        assertThat(event.eventId()).isEqualTo(UUID.fromString("00000000-0000-4000-8000-000000000005"));
        assertThat(event.orderId()).isEqualTo(7);
        assertThat(event.couponCode()).isNull();
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-11-13T10:00:00Z"));
    }

    /** Mutation: the code read from "coupon" (always null, so never counted). */
    @Test
    void anOrderWithACouponCarriesItsCode() {
        assertThat(parse(withCoupon("SPRING10")).couponCode()).isEqualTo(new CouponCode("SPRING10"));
    }

    /**
     * platform-service's fields will grow the event: an unknown field, at the
     * top or in a merchant, is ignored. Mutation: the top-level names checked
     * against the fields the parser reads.
     */
    @Test
    void unknownFieldsAreIgnored() {
        String value = goldenWith(event -> {
            event.put("platformField", SENTINEL);
            ((ObjectNode) event.get("merchants").get(0)).put("merchantField", SENTINEL);
        });

        assertThat(parse(value).orderId()).isEqualTo(7);
    }

    /**
     * Every money value order-service writes is at scale 2; 0 and 0.00 are both
     * a discount of nothing. Mutation: at most 1 decimal place.
     */
    @Test
    void aDiscountOfZeroOrAtTwoDecimalPlacesIsAccepted() {
        for (String discount : new String[] {"0", "0.00", "9999999999.99"}) {
            String value = goldenWith(event ->
                    ((ObjectNode) event.get("merchants").get(0)).put("discount", new java.math.BigDecimal(discount)));
            assertThat(parse(value).orderId()).as(discount).isEqualTo(7);
        }
    }

    // ---- refused: the record itself -----------------------------------------------

    /** A tombstone. Mutation: a null value read as no bytes ("not a JSON object" instead). */
    @Test
    void aRecordWithNoValueIsRefused() {
        assertThatThrownBy(() -> PARSER.parse(null))
                .isInstanceOf(InvalidOrderSucceededException.class)
                .hasMessage("The record has no value")
                .hasNoCause();
    }

    /**
     * Bytes that are not UTF-8: a lone continuation byte, a truncated sequence,
     * 0xFF, an encoded surrogate and an overlong slash. Mutation: decoded with
     * {@code new String(bytes, UTF_8)} (replacement characters, and a value of
     * the golden event's shape is then counted).
     */
    @Test
    void aValueThatIsNotUtf8IsRefused() {
        String golden = golden();
        int code = golden.indexOf("\"couponCode\":null");
        for (byte[] bad : new byte[][] {{(byte) 0x80}, {(byte) 0xC3}, {(byte) 0xFF}, {(byte) 0xED, (byte) 0xA0,
                (byte) 0x80}, {(byte) 0xC0, (byte) 0xAF}}) {
            // Inside a JSON string, so the value is otherwise the golden event.
            byte[] before = golden.substring(0, code).getBytes(StandardCharsets.UTF_8);
            byte[] after = ("\"x\":\"" + "\",").getBytes(StandardCharsets.UTF_8);
            byte[] rest = golden.substring(code).getBytes(StandardCharsets.UTF_8);
            byte[] value = new byte[before.length + 5 + bad.length + 2 + rest.length];
            System.arraycopy(before, 0, value, 0, before.length);
            System.arraycopy(after, 0, value, before.length, 5);
            System.arraycopy(bad, 0, value, before.length + 5, bad.length);
            System.arraycopy(after, 5, value, before.length + 5 + bad.length, 2);
            System.arraycopy(rest, 0, value, before.length + 5 + bad.length + 2, rest.length);

            assertThatThrownBy(() -> PARSER.parse(value)).as(java.util.HexFormat.of().formatHex(bad))
                    .isInstanceOf(InvalidOrderSucceededException.class)
                    .hasMessage("The value is not valid UTF-8")
                    .hasNoCause();
        }
    }

    /**
     * Not JSON, a repeated field and a token after the object: only the
     * position, never Jackson's message (it quotes the input). Mutations:
     * STRICT_DUPLICATE_DETECTION off (the repeated orderId is taken);
     * FAIL_ON_TRAILING_TOKENS off; Jackson's message appended.
     */
    @Test
    void aValueThatIsNotOneJsonObjectIsRefusedWithItsPositionOnly() {
        for (String value : new String[] {SENTINEL,
                golden().replace("{\"schemaVersion\":1,", "{\"schemaVersion\":1,\"orderId\":8,"),
                golden() + " " + SENTINEL}) {
            assertThatThrownBy(() -> parse(value)).as(value)
                    .isInstanceOf(InvalidOrderSucceededException.class)
                    .hasMessageMatching("The value is not valid JSON \\(line 1, column [0-9]+\\)")
                    .hasNoCause();
        }
    }

    // ---- refused: one field --------------------------------------------------------

    static Stream<Arguments> refusals() {
        String version = "schemaVersion is missing or not 1";
        String eventId = "eventId must be a UUID string";
        String orderId = "orderId must be a positive whole JSON number that fits 64 bits";
        String couponMissing = "couponCode is required (null for no coupon)";
        String coupon = "couponCode must be null or a normalised coupon code";
        String occurredAt = "occurredAt must be an ISO-8601 instant string";
        String merchants = "merchants must be a non-empty array of objects";
        String merchantId = "merchants[0].merchantId must be a positive whole JSON number that fits 64 bits";
        String discount = "merchants[0].discount must be a JSON number, 0 or more, with at most 2 decimal places";
        return Stream.of(
                Arguments.of("an array", "[" + golden() + "]", "The value is not a JSON object"),
                Arguments.of("a string", "\"" + SENTINEL + "\"", "The value is not a JSON object"),
                // schemaVersion first: nothing else is looked at.
                Arguments.of("schemaVersion 2 alone", "{\"schemaVersion\":2}", version),
                Arguments.of("schemaVersion missing", goldenWith(e -> e.remove("schemaVersion")), version),
                Arguments.of("schemaVersion \"1\"", goldenWith(e -> e.put("schemaVersion", "1")), version),
                Arguments.of("schemaVersion 1.0", goldenWith(e -> e.put("schemaVersion", new java.math.BigDecimal("1.0"))), version),
                Arguments.of("eventId missing", goldenWith(e -> e.remove("eventId")), eventId),
                Arguments.of("eventId a number", goldenWith(e -> e.put("eventId", 5)), eventId),
                Arguments.of("eventId not a UUID", goldenWith(e -> e.put("eventId", SENTINEL)), eventId),
                Arguments.of("eventId a short UUID", goldenWith(e -> e.put("eventId", "1-1-1-1-1")), eventId),
                Arguments.of("orderId missing", goldenWith(e -> e.remove("orderId")), orderId),
                Arguments.of("orderId 0", goldenWith(e -> e.put("orderId", 0)), orderId),
                Arguments.of("orderId 7.0", goldenWith(e -> e.put("orderId", new java.math.BigDecimal("7.0"))), orderId),
                Arguments.of("orderId \"7\"", goldenWith(e -> e.put("orderId", "7")), orderId),
                Arguments.of("orderId past 64 bits", goldenWith(e -> e.put("orderId",
                        new java.math.BigInteger("9223372036854775808"))), orderId),
                Arguments.of("couponCode missing", goldenWith(e -> e.remove("couponCode")), couponMissing),
                Arguments.of("couponCode a number", goldenWith(e -> e.put("couponCode", 10)), coupon),
                Arguments.of("couponCode not normalised", withCoupon("spring10"), coupon),
                Arguments.of("couponCode with a space", withCoupon(" SPRING10"), coupon),
                Arguments.of("couponCode too short", withCoupon("SP"), coupon),
                Arguments.of("couponCode a sentinel with a dot", withCoupon(SENTINEL + "."), coupon),
                Arguments.of("occurredAt missing", goldenWith(e -> e.remove("occurredAt")), occurredAt),
                Arguments.of("occurredAt a number", goldenWith(e -> e.put("occurredAt", 1)), occurredAt),
                Arguments.of("occurredAt not a time", goldenWith(e -> e.put("occurredAt", SENTINEL)), occurredAt),
                Arguments.of("occurredAt a date", goldenWith(e -> e.put("occurredAt", "2026-11-13")), occurredAt),
                Arguments.of("merchants missing", goldenWith(e -> e.remove("merchants")), merchants),
                Arguments.of("merchants empty", goldenWith(e -> e.putArray("merchants")), merchants),
                Arguments.of("merchants an object", goldenWith(e -> e.putObject("merchants")), merchants),
                Arguments.of("a merchant a number", goldenWith(e -> ((ArrayNode) e.get("merchants")).set(0, e.numberNode(3))), merchants),
                Arguments.of("merchantId missing", goldenWith(e -> merchant(e).remove("merchantId")), merchantId),
                Arguments.of("merchantId \"3\"", goldenWith(e -> merchant(e).put("merchantId", "3")), merchantId),
                Arguments.of("discount missing", goldenWith(e -> merchant(e).remove("discount")), discount),
                Arguments.of("discount \"0.00\"", goldenWith(e -> merchant(e).put("discount", "0.00")), discount),
                Arguments.of("discount negative", goldenWith(e -> merchant(e).put("discount",
                        new java.math.BigDecimal("-0.01"))), discount),
                Arguments.of("discount at 3 places", goldenWith(e -> merchant(e).put("discount",
                        new java.math.BigDecimal("0.001"))), discount));
    }

    private static ObjectNode merchant(ObjectNode event) {
        return (ObjectNode) event.get("merchants").get(0);
    }

    /**
     * Each refusal names its field and never quotes the value (the message goes
     * into a dead-letter header and a log line). Mutations, each one case: the
     * schemaVersion not checked; orderId's {@code <= 0} as {@code < 0};
     * the couponCode read with {@code CouponCode.normalise} (spring10
     * accepted); occurredAt not required; an empty merchants accepted; the
     * discount's scale not checked; a negative discount accepted.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void anEventBreakingARuleIsRefusedWithOurOwnMessage(String name, String value, String message) {
        assertThatThrownBy(() -> parse(value))
                .isInstanceOf(InvalidOrderSucceededException.class)
                .hasMessage(message)
                .hasNoCause()
                .message().doesNotContain(SENTINEL);
    }
}

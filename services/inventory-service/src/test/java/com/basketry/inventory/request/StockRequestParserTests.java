package com.basketry.inventory.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.inventory.stock.InvalidStockRequestException;
import com.basketry.inventory.stock.ReleaseCommand;
import com.basketry.inventory.stock.ReserveCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The request contract (DESIGN.md Section 5, inventory-service requests): what
 * is accepted, and that everything else is refused with a message that quotes
 * nothing from the record.
 */
class StockRequestParserTests {

    /** In every refused value below, so a message that quotes the record shows it. */
    private static final String SENTINEL = "918273645";

    private final StockRequestParser parser = new StockRequestParser(JsonMapper.builder().build());

    private static String request(String quantity) {
        return """
                {"schemaVersion":1,"eventId":"6f1c2c1e-1b7a-4f55-9d55-1b5e0c7c9a10","orderId":7,"productId":42,\
                "quantity":%s,"occurredAt":"2026-11-09T10:00:00Z"}""".formatted(quantity);
    }

    @Test
    void aReserveRequestBecomesACommand() {
        assertThat(parser.parseReserve(request("3"))).isEqualTo(new ReserveCommand(7, 42, 3));
    }

    @Test
    void aReleaseRequestBecomesACommand() {
        assertThat(parser.parseRelease(request("3"))).isEqualTo(new ReleaseCommand(7, 42, 3));
    }

    @Test
    void eventIdAndOccurredAtAreOptionalAndIdsGoUpToLongMax() {
        assertThat(parser.parseReserve("""
                {"schemaVersion":1,"orderId":9223372036854775807,"productId":9223372036854775807,"quantity":1000}"""))
                .isEqualTo(new ReserveCommand(Long.MAX_VALUE, Long.MAX_VALUE, 1000));
    }

    /**
     * A positive whole number of any size is a valid request. Up to
     * Integer.MAX_VALUE it is kept as it is (StockService answers anything
     * above 1,000 with QUANTITY_OVER_LIMIT); above, it becomes Integer.MAX_VALUE
     * and never wraps around. 4294967301 is 2^32 + 5: read as an int, it would
     * be 5, and 5 would be reserved.
     */
    @ParameterizedTest
    @CsvSource({
        "1, 1",
        "1000, 1000",
        "1001, 1001",
        "2147483647, 2147483647",
        "2147483648, 2147483647",
        "4294967301, 2147483647",
        "9223372036854775808, 2147483647",
        "1000000000000000000000000000000, 2147483647"})
    void aQuantityOfAnySizeIsKeptOrCappedNeverWrapped(String sent, int expected) {
        assertThat(parser.parseReserve(request(sent)).quantity()).isEqualTo(expected);
        assertThat(parser.parseRelease(request(sent)).quantity()).isEqualTo(expected);
    }

    /** Zero, negative and anything that is not a whole JSON number is malformed: the dead-letter topic. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0", "-1000000000000000000000000000000", "1.5", "1.0", "1e3", "1E3",
        "\"5\"", "true", "null", "[5]", "{}"})
    void aQuantityThatIsNotAPositiveWholeNumberIsRefused(String quantity) {
        assertThatThrownBy(() -> parser.parseReserve(request(quantity)))
                .isInstanceOf(InvalidStockRequestException.class).hasNoCause();
        assertThatThrownBy(() -> parser.parseRelease(request(quantity)))
                .isInstanceOf(InvalidStockRequestException.class).hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // not JSON, not an object, or more than one value
        "not json " + SENTINEL,
        "{\"schemaVersion\":1,\"orderId\":" + SENTINEL + "x}",
        "[" + SENTINEL + "]",
        "\"" + SENTINEL + "\"",
        "",
        "{\"schemaVersion\":1,\"orderId\":7,\"productId\":42,\"quantity\":" + SENTINEL + "} {\"x\":1}",
        // schemaVersion first: missing, another version, or not the JSON number 1
        "{\"orderId\":7,\"productId\":42,\"quantity\":" + SENTINEL + "}",
        "{\"schemaVersion\":2,\"orderId\":7,\"productId\":42,\"quantity\":" + SENTINEL + "}",
        "{\"schemaVersion\":\"1\",\"orderId\":7,\"productId\":42,\"quantity\":" + SENTINEL + "}",
        "{\"schemaVersion\":1.0,\"orderId\":7,\"productId\":42,\"quantity\":" + SENTINEL + "}",
        "{\"schemaVersion\":" + SENTINEL + ",\"orderId\":7,\"productId\":42,\"quantity\":1}",
        // unknown fields, also one named like a value
        "{\"schemaVersion\":1,\"orderId\":7,\"productId\":42,\"quantity\":1,\"warehouse\":" + SENTINEL + "}",
        "{\"schemaVersion\":1,\"orderId\":7,\"productId\":42,\"quantity\":1,\"" + SENTINEL + "\":1}",
        // a payment field is unknown here
        "{\"schemaVersion\":1,\"orderId\":7,\"productId\":42,\"quantity\":1,\"merchantId\":" + SENTINEL + "}",
        // a repeated field: the last one would otherwise win
        "{\"schemaVersion\":1,\"orderId\":7,\"productId\":42,\"quantity\":1,\"quantity\":" + SENTINEL + "}",
        // ids: positive whole JSON numbers that fit a long, nothing else
        "{\"schemaVersion\":1,\"orderId\":\"7\",\"productId\":" + SENTINEL + ",\"quantity\":1}",
        "{\"schemaVersion\":1,\"orderId\":7.0,\"productId\":" + SENTINEL + ",\"quantity\":1}",
        "{\"schemaVersion\":1,\"orderId\":7,\"productId\":" + SENTINEL + ".5,\"quantity\":1}",
        "{\"schemaVersion\":1,\"orderId\":9223372036854775808,\"productId\":" + SENTINEL + ",\"quantity\":1}",
        "{\"schemaVersion\":1,\"orderId\":0,\"productId\":" + SENTINEL + ",\"quantity\":1}",
        "{\"schemaVersion\":1,\"orderId\":-7,\"productId\":" + SENTINEL + ",\"quantity\":1}",
        "{\"schemaVersion\":1,\"orderId\":7,\"productId\":-" + SENTINEL + ",\"quantity\":1}",
        "{\"schemaVersion\":1,\"orderId\":" + SENTINEL + ",\"quantity\":1}",
        "{\"schemaVersion\":1,\"orderId\":" + SENTINEL + ",\"productId\":null,\"quantity\":1}",
        // quantity is required
        "{\"schemaVersion\":1,\"orderId\":7,\"productId\":" + SENTINEL + "}",
        // the optional fields are strings when present
        "{\"schemaVersion\":1,\"eventId\":" + SENTINEL + ",\"orderId\":7,\"productId\":42,\"quantity\":1}",
        "{\"schemaVersion\":1,\"occurredAt\":" + SENTINEL + ",\"orderId\":7,\"productId\":42,\"quantity\":1}"})
    void aBadRequestIsRefusedWithoutQuotingIt(String value) {
        for (Runnable parse : new Runnable[] {() -> parser.parseReserve(value), () -> parser.parseRelease(value)}) {
            assertThatThrownBy(parse::run)
                    .isInstanceOf(InvalidStockRequestException.class)
                    .hasNoCause()
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SENTINEL));
        }
    }

    @Test
    void aMissingValueIsRefused() {
        assertThatThrownBy(() -> parser.parseReserve(null))
                .isInstanceOf(InvalidStockRequestException.class)
                .hasMessage("The record has no value");
    }

    /** Each rule says which one failed, so a dead letter can be understood without its value. */
    @Test
    void theMessageNamesTheRule() {
        assertThatThrownBy(() -> parser.parseReserve("{\"schemaVersion\":2}"))
                .hasMessage("schemaVersion is missing or not 1");
        assertThatThrownBy(() -> parser.parseReserve(
                "{\"schemaVersion\":1,\"orderId\":7,\"productId\":42,\"quantity\":1,\"x\":1}"))
                .hasMessage("The value has a field that schemaVersion 1 does not have");
        assertThatThrownBy(() -> parser.parseReserve("{\"schemaVersion\":1,\"quantity\":1,\"quantity\":2}"))
                .hasMessageStartingWith("The value is not valid JSON (line 1, column");
        assertThatThrownBy(() -> parser.parseReserve(request("1.5")))
                .hasMessage("quantity must be a whole JSON number");
        assertThatThrownBy(() -> parser.parseReserve(request("0")))
                .hasMessage("quantity must be positive");
        assertThatThrownBy(() -> parser.parseReserve(
                "{\"schemaVersion\":1,\"orderId\":0,\"productId\":42,\"quantity\":1}"))
                .hasMessage("orderId must be positive");
        assertThatThrownBy(() -> parser.parseReserve(
                "{\"schemaVersion\":1,\"orderId\":7,\"productId\":\"42\",\"quantity\":1}"))
                .hasMessage("productId must be a whole JSON number that fits 64 bits");
    }
}

package com.basketry.payment.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.payment.payment.ChargeCommand;
import com.basketry.payment.payment.InvalidPaymentRequestException;
import com.basketry.payment.payment.RefundCommand;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The request contract (DESIGN.md Section 5, payment-service requests): what
 * is accepted, and that everything else is refused with a message that quotes
 * nothing from the record.
 */
class PaymentRequestParserTests {

    private static final String REF = "TXN-0123456789abcdef0123456789abcdef";
    /** Approved by the mock; every refused case below carries it somewhere. */
    private static final String SENTINEL = "4929117300568841";
    private static final String SENTINEL_HEAD = SENTINEL.substring(0, 12);

    private final PaymentRequestParser parser = new PaymentRequestParser(JsonMapper.builder().build());

    private static String charge(String amount, String cardNumber) {
        return """
                {"schemaVersion":1,"eventId":"6f1c2c1e-1b7a-4f55-9d55-1b5e0c7c9a10","orderId":7,"merchantId":3,\
                "amount":%s,"cardNumber":%s,"occurredAt":"2026-11-08T10:00:00Z"}""".formatted(amount, cardNumber);
    }

    @Test
    void aChargeRequestBecomesACommand() {
        ChargeCommand command = parser.parseCharge(charge("25.50", "\"" + SENTINEL + "\""));

        assertThat(command.orderId()).isEqualTo(7);
        assertThat(command.merchantId()).isEqualTo(3);
        assertThat(command.amount()).isEqualTo(new BigDecimal("25.50"));
        assertThat(command.cardNumber()).isEqualTo(SENTINEL);
    }

    @Test
    void aRefundRequestBecomesACommand() {
        RefundCommand command = parser.parseRefund("""
                {"schemaVersion":1,"eventId":"e","orderId":7,"merchantId":3,"transactionRef":"%s",\
                "occurredAt":"x"}""".formatted(REF));

        assertThat(command).isEqualTo(new RefundCommand(7, 3, REF));
    }

    @Test
    void eventIdAndOccurredAtAreOptional() {
        assertThat(parser.parseCharge("""
                {"schemaVersion":1,"orderId":9223372036854775807,"merchantId":1,"amount":9999999999.99,\
                "cardNumber":"4000000000000002"}""").orderId()).isEqualTo(Long.MAX_VALUE);
    }

    /** Read as a decimal from the text: as a double, this would become 0.1 and be accepted. */
    @Test
    void anAmountIsNeverReadAsADouble() {
        assertThat(parser.parseCharge(charge("0.10", "\"" + SENTINEL + "\"")).amount())
                .isEqualTo(new BigDecimal("0.10"));
        assertThatThrownBy(() -> parser.parseCharge(charge("0.1000000000000000001", "\"" + SENTINEL + "\"")))
                .isInstanceOf(InvalidPaymentRequestException.class)
                .hasMessage("amount has more than 2 decimal places");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // not JSON, not an object, or more than one value
        "not json " + SENTINEL,
        "{\"schemaVersion\":1,\"cardNumber\":" + SENTINEL + "x}",
        "[\"" + SENTINEL + "\"]",
        "\"" + SENTINEL + "\"",
        "",
        "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"amount\":1,\"cardNumber\":\"" + SENTINEL
                + "\"} {\"x\":1}",
        // schemaVersion first: missing, another version, or not the JSON number 1
        "{\"orderId\":7,\"merchantId\":3,\"amount\":1,\"cardNumber\":\"" + SENTINEL + "\"}",
        "{\"schemaVersion\":2,\"orderId\":7,\"merchantId\":3,\"amount\":1,\"cardNumber\":\"" + SENTINEL + "\"}",
        "{\"schemaVersion\":\"1\",\"orderId\":7,\"merchantId\":3,\"amount\":1,\"cardNumber\":\"" + SENTINEL + "\"}",
        "{\"schemaVersion\":1.0,\"orderId\":7,\"merchantId\":3,\"amount\":1,\"cardNumber\":\"" + SENTINEL + "\"}",
        "{\"schemaVersion\":" + SENTINEL + ",\"orderId\":7,\"merchantId\":3,\"amount\":1,\"cardNumber\":\"1\"}",
        // unknown field, also one named like a card number
        "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"amount\":1,\"cardNumber\":\"4000000000000002\","
                + "\"currency\":\"EUR\"}",
        "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"amount\":1,\"cardNumber\":\"4000000000000002\","
                + "\"" + SENTINEL + "\":1}",
        // a repeated field: the last one would otherwise win
        "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"amount\":1,\"amount\":1000,"
                + "\"cardNumber\":\"" + SENTINEL + "\"}",
        // ids: whole JSON numbers that fit a long, nothing else
        "{\"schemaVersion\":1,\"orderId\":\"7\",\"merchantId\":3,\"amount\":1,\"cardNumber\":\"" + SENTINEL + "\"}",
        "{\"schemaVersion\":1,\"orderId\":7.0,\"merchantId\":3,\"amount\":1,\"cardNumber\":\"" + SENTINEL + "\"}",
        "{\"schemaVersion\":1,\"orderId\":7.5,\"merchantId\":3,\"amount\":1,\"cardNumber\":\"" + SENTINEL + "\"}",
        "{\"schemaVersion\":1,\"orderId\":9223372036854775808,\"merchantId\":3,\"amount\":1,"
                + "\"cardNumber\":\"" + SENTINEL + "\"}",
        "{\"schemaVersion\":1,\"orderId\":7,\"amount\":1,\"cardNumber\":\"" + SENTINEL + "\"}",
        "{\"schemaVersion\":1,\"orderId\":0,\"merchantId\":3,\"amount\":1,\"cardNumber\":\"" + SENTINEL + "\"}",
        // amount: a JSON number
        "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"amount\":\"25.50\",\"cardNumber\":\"" + SENTINEL
                + "\"}",
        "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"amount\":null,\"cardNumber\":\"" + SENTINEL + "\"}",
        // cardNumber: a JSON string of 12 to 19 digits
        "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"amount\":1,\"cardNumber\":" + SENTINEL + "}",
        "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"amount\":1,\"cardNumber\":\"" + SENTINEL + "X\"}",
        "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"amount\":1}",
        // the optional fields are strings when present
        "{\"schemaVersion\":1,\"eventId\":" + SENTINEL + ",\"orderId\":7,\"merchantId\":3,\"amount\":1,"
                + "\"cardNumber\":\"4000000000000002\"}"})
    void aBadChargeRequestIsRefusedWithoutQuotingIt(String value) {
        assertThatThrownBy(() -> parser.parseCharge(value))
                .isInstanceOf(InvalidPaymentRequestException.class)
                .hasNoCause()
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SENTINEL_HEAD));
    }

    @Test
    void aMissingValueIsRefused() {
        assertThatThrownBy(() -> parser.parseCharge(null))
                .isInstanceOf(InvalidPaymentRequestException.class)
                .hasMessage("The record has no value");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"transactionRef\":123}",
        "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"transactionRef\":\"TXN-nope\"}",
        "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3}",
        // a charge's fields are unknown to a refund
        "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"transactionRef\":\"" + REF + "\",\"amount\":1}"})
    void aBadRefundRequestIsRefused(String value) {
        assertThatThrownBy(() -> parser.parseRefund(value))
                .isInstanceOf(InvalidPaymentRequestException.class)
                .hasNoCause();
    }

    /** Each rule says which one failed, so a dead letter can be understood without its value. */
    @Test
    void theMessageNamesTheRule() {
        assertThatThrownBy(() -> parser.parseCharge("{\"schemaVersion\":2}"))
                .hasMessage("schemaVersion is missing or not 1");
        assertThatThrownBy(() -> parser.parseCharge(
                "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"amount\":1,\"cardNumber\":\"1\",\"x\":1}"))
                .hasMessage("The value has a field that schemaVersion 1 does not have");
        assertThatThrownBy(() -> parser.parseCharge("{\"schemaVersion\":1,\"amount\":1,\"amount\":2}"))
                .hasMessageStartingWith("The value is not valid JSON (line 1, column");
        assertThatThrownBy(() -> parser.parseCharge(
                "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"amount\":1,\"cardNumber\":4}"))
                .hasMessage("cardNumber must be a JSON string");
        assertThatThrownBy(() -> parser.parseCharge(
                "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"amount\":\"1\",\"cardNumber\":\"1\"}"))
                .hasMessage("amount must be a JSON number");
    }
}

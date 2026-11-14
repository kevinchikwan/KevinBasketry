package com.basketry.order.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.basketry.order.saga.SagaResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** Strict parsing of the six results (DESIGN.md Section 5, order-service results). */
class ResultParserTests {

    private static final ResultParser PARSER = new ResultParser(JsonMapper.builder().build());
    private static final String REF = "TXN-0123456789abcdef0123456789abcdef";

    private static InvalidResultException refused(String topic, String value) {
        return catchThrowableOfType(InvalidResultException.class, () -> PARSER.parse(topic, value));
    }

    /**
     * payment-service's and inventory-service's real payloads (their result
     * records, eventId and occurredAt included). Mutations: merchantId and
     * productId swapped for a reserve; the reasonCode not read.
     */
    @Test
    void theSixResultsAsTheirServicesPublishThem() {
        String head = "{\"schemaVersion\":1,\"eventId\":\"6f1c0e0e-0000-4000-8000-000000000001\",\"orderId\":7,";
        String tail = ",\"occurredAt\":\"2026-11-08T10:00:00Z\"}";

        assertThat(PARSER.parse(ResultTopics.CHARGED, head + "\"merchantId\":3,\"transactionRef\":\"" + REF + "\"" + tail))
                .isEqualTo(new SagaResult.Charged(7, 3, REF));
        assertThat(PARSER.parse(ResultTopics.DECLINED, head + "\"merchantId\":3,\"reasonCode\":\"51\"" + tail))
                .isEqualTo(new SagaResult.Declined(7, 3, "51"));
        assertThat(PARSER.parse(ResultTopics.REFUNDED, head + "\"merchantId\":3" + tail))
                .isEqualTo(new SagaResult.Refunded(7, 3));
        assertThat(PARSER.parse(ResultTopics.RESERVED, head + "\"productId\":42" + tail))
                .isEqualTo(new SagaResult.Reserved(7, 42));
        assertThat(PARSER.parse(ResultTopics.RESERVATION_FAILED,
                head + "\"productId\":42,\"reasonCode\":\"INSUFFICIENT_STOCK\"" + tail))
                .isEqualTo(new SagaResult.ReservationFailed(7, 42, "INSUFFICIENT_STOCK"));
        assertThat(PARSER.parse(ResultTopics.RELEASED, head + "\"productId\":42" + tail))
                .isEqualTo(new SagaResult.Released(7, 42));
    }

    /** Unknown fields are ignored, as the other consumers do. Mutation: unknown fields refused. */
    @Test
    void anUnknownFieldIsIgnored() {
        assertThat(PARSER.parse(ResultTopics.REFUNDED, "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"x\":true}"))
                .isEqualTo(new SagaResult.Refunded(7, 3));
    }

    /**
     * schemaVersion first and only 1. Mutations: the schemaVersion check
     * removed; 1.0 accepted (isNumber for isIntegralNumber).
     */
    @ParameterizedTest
    @ValueSource(strings = {"{\"orderId\":7,\"merchantId\":3}", "{\"schemaVersion\":2,\"orderId\":7,\"merchantId\":3}",
            "{\"schemaVersion\":1.0,\"orderId\":7,\"merchantId\":3}", "{\"schemaVersion\":\"1\",\"orderId\":7,\"merchantId\":3}"})
    void anotherSchemaVersionIsRefused(String value) {
        assertThat(refused(ResultTopics.REFUNDED, value).getMessage()).isEqualTo("schemaVersion is missing or not 1");
    }

    /**
     * Ids: positive whole JSON numbers that fit 64 bits. Mutations: {@code <= 0}
     * as {@code < 0}; canConvertToLong dropped (a huge id wraps).
     */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "7.0", "\"7\"", "99999999999999999999", "null"})
    void aBadIdIsRefused(String id) {
        assertThat(refused(ResultTopics.REFUNDED, "{\"schemaVersion\":1,\"orderId\":" + id + ",\"merchantId\":3}"))
                .isNotNull();
    }

    /** Mutation: any string accepted as a transactionRef. */
    @ParameterizedTest
    @ValueSource(strings = {"\"TXN-0123\"", "\"TXN-0123456789ABCDEF0123456789ABCDEF\"", "\"0123456789abcdef0123456789abcdef\"",
            "7"})
    void aTransactionRefNotInPaymentServicesFormatIsRefused(String ref) {
        assertThat(refused(ResultTopics.CHARGED,
                "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"transactionRef\":" + ref + "}")).isNotNull();
    }

    /** 1 to 40 characters. Mutation: the length check removed (41 accepted, too long for the column). */
    @Test
    void aReasonCodeIsOneToFortyCharacters() {
        String value = "{\"schemaVersion\":1,\"orderId\":7,\"productId\":42,\"reasonCode\":%s}";

        assertThat(PARSER.parse(ResultTopics.RESERVATION_FAILED, value.formatted("\"" + "R".repeat(40) + "\"")))
                .isNotNull();
        assertThat(refused(ResultTopics.RESERVATION_FAILED, value.formatted("\"" + "R".repeat(41) + "\""))).isNotNull();
        assertThat(refused(ResultTopics.RESERVATION_FAILED, value.formatted("\"\""))).isNotNull();
        assertThat(refused(ResultTopics.RESERVATION_FAILED, value.formatted("51"))).isNotNull();
    }

    /**
     * A repeated field and anything after the object are refused; the message
     * quotes no value. Mutation: STRICT_DUPLICATE_DETECTION off (the last
     * merchantId would win). FAIL_ON_TRAILING_TOKENS is on by default in
     * Jackson 3, so turning it off in the reader changes nothing; the explicit
     * setting only states the rule.
     */
    @Test
    void aRepeatedFieldOrTrailingTextIsRefusedWithoutQuotingIt() {
        InvalidResultException repeated = refused(ResultTopics.REFUNDED,
                "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"merchantId\":4}");
        InvalidResultException trailing = refused(ResultTopics.REFUNDED,
                "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3} {\"secret-4929\":1}");
        InvalidResultException notJson = refused(ResultTopics.REFUNDED, "{\"secret-4929\":");

        assertThat(repeated.getMessage()).startsWith("The value is not valid JSON").doesNotContain("merchantId");
        assertThat(trailing.getMessage()).startsWith("The value is not valid JSON").doesNotContain("secret");
        assertThat(notJson.getMessage()).doesNotContain("secret");
        assertThat(repeated.getCause()).isNull();
    }

    /** eventId and occurredAt are strings when present. Mutation: the optional-string check removed. */
    @Test
    void eventIdAndOccurredAtAreStringsWhenPresent() {
        assertThat(refused(ResultTopics.REFUNDED, "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"eventId\":7}"))
                .isNotNull();
        assertThat(refused(ResultTopics.REFUNDED,
                "{\"schemaVersion\":1,\"orderId\":7,\"merchantId\":3,\"occurredAt\":1700000000}")).isNotNull();
        assertThat(refused(ResultTopics.REFUNDED, null).getMessage()).isEqualTo("The record has no value");
    }
}

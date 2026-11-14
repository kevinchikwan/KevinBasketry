package com.basketry.order.results;

import java.util.List;

/**
 * The six result topics order-service reads (DESIGN.md Section 5). They belong
 * to payment-service and inventory-service, which create them; order-service
 * never does. Each has order-service's own dead-letter topic, the name plus
 * {@value #DEAD_LETTER_SUFFIX}.
 */
public final class ResultTopics {

    public static final String CHARGED = "order.merchant.charged";
    public static final String DECLINED = "order.merchant.declined";
    public static final String REFUNDED = "order.merchant.refunded";
    public static final String RESERVED = "order.stock.reserved";
    public static final String RESERVATION_FAILED = "order.stock.reservation-failed";
    public static final String RELEASED = "order.stock.released";

    public static final List<String> ALL = List.of(CHARGED, DECLINED, REFUNDED, RESERVED, RESERVATION_FAILED, RELEASED);

    public static final String DEAD_LETTER_SUFFIX = ".order-service.DLT";

    private ResultTopics() {
    }

    public static String deadLetterTopicOf(String topic) {
        return topic + DEAD_LETTER_SUFFIX;
    }
}

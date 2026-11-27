package com.basketry.promotion.usage;

/**
 * The topic promotion-service reads for the usage count (DESIGN.md Section 5).
 * It belongs to order-service, which creates it; promotion-service never does.
 * Its dead-letter topic is promotion-service's own, created here on first use.
 */
public final class OrderSucceededTopic {

    public static final String NAME = "order.succeeded";
    public static final String DEAD_LETTER = NAME + ".promotion-service.DLT";

    private OrderSucceededTopic() {
    }
}

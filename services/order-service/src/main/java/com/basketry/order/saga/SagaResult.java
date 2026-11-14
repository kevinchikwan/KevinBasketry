package com.basketry.order.saga;

/**
 * One result from payment-service or inventory-service, parsed (DESIGN.md
 * Section 5, order-service results). Matched to its leg by (orderId,
 * merchantId) or (orderId, productId).
 */
public sealed interface SagaResult {

    long orderId();

    record Charged(long orderId, long merchantId, String transactionRef) implements SagaResult {
    }

    record Declined(long orderId, long merchantId, String reasonCode) implements SagaResult {
    }

    record Refunded(long orderId, long merchantId) implements SagaResult {
    }

    record Reserved(long orderId, long productId) implements SagaResult {
    }

    record ReservationFailed(long orderId, long productId, String reasonCode) implements SagaResult {
    }

    record Released(long orderId, long productId) implements SagaResult {
    }
}

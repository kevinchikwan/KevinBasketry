package com.basketry.payment.payment;

/** The answer to a refund request, also for a repeated one. Published as {@code order.merchant.refunded}. */
public record Refunded(long orderId, long merchantId, String transactionRef) {
}

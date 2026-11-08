package com.basketry.payment.payment;

/**
 * The answer to a charge request, also for a repeated one: what the first
 * request decided, which is what gets published.
 */
public sealed interface ChargeOutcome {

    /** Published as {@code order.merchant.charged}. Also the answer for a charge refunded since. */
    record Charged(String transactionRef) implements ChargeOutcome {
    }

    /** Published as {@code order.merchant.declined}, with the response code as {@code reasonCode}. */
    record Declined(String responseCode) implements ChargeOutcome {
    }
}

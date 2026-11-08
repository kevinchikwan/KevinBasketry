package com.basketry.payment.payment;

/**
 * One refund request: {@code order.merchant-refund.requested} without its
 * transport (DESIGN.md Section 5). The transaction is found by
 * {@code transactionRef}; {@code orderId} and {@code merchantId} must match it.
 */
public record RefundCommand(long orderId, long merchantId, String transactionRef) {

    public RefundCommand {
        if (orderId <= 0) {
            throw new InvalidPaymentRequestException("orderId must be positive");
        }
        if (merchantId <= 0) {
            throw new InvalidPaymentRequestException("merchantId must be positive");
        }
        if (!TransactionRefs.isWellFormed(transactionRef)) {
            throw new InvalidPaymentRequestException("transactionRef is not a transaction reference");
        }
    }
}

package com.basketry.payment.payment;

/**
 * A refund for a reference this service never issued. order-service learns a
 * reference only from a committed charge, so this means data loss (an H2
 * restart, a wiped database) or a bug, never a refund that overtook its charge.
 */
public class UnknownTransactionException extends PaymentRequestRejectedException {

    public UnknownTransactionException(String transactionRef) {
        super("No transaction " + transactionRef);
    }
}

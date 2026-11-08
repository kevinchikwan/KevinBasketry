package com.basketry.payment.payment;

/**
 * A refund of a DECLINED transaction. Nothing was charged, and order-service
 * never gets a reference for a decline, so this is a bug on the caller's side.
 */
public class NotRefundableException extends PaymentRequestRejectedException {

    public NotRefundableException(String transactionRef, TransactionStatus status) {
        super("Transaction " + transactionRef + " is " + status + " and cannot be refunded");
    }
}

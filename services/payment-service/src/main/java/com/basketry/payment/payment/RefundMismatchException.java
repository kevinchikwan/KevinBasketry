package com.basketry.payment.payment;

/** A refund whose orderId or merchantId is not the one of the referenced transaction. */
public class RefundMismatchException extends PaymentRequestRejectedException {

    public RefundMismatchException(RefundCommand command, PaymentTransaction stored) {
        super("Transaction " + command.transactionRef() + " belongs to order " + stored.getOrderId()
                + " and merchant " + stored.getMerchantId() + ", not to order " + command.orderId()
                + " and merchant " + command.merchantId());
    }
}

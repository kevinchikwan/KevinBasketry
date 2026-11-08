package com.basketry.payment.payment;

/**
 * A request that can never succeed, however often it is retried. Nothing is
 * changed and no result is published. The Kafka listener will send the record
 * to its dead-letter topic (DESIGN.md Section 5). The message never contains
 * the card number.
 */
public abstract class PaymentRequestRejectedException extends RuntimeException {

    protected PaymentRequestRejectedException(String message) {
        super(message);
    }
}

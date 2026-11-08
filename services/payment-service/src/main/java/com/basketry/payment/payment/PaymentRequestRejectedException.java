package com.basketry.payment.payment;

/**
 * A request that can never succeed, however often it is retried. Nothing is
 * changed and no result is published. The Kafka listener sends the record to
 * its dead-letter topic, where the message is a header (DESIGN.md Section 5).
 * The message never quotes a value from the request, the card number least of
 * all.
 */
public abstract class PaymentRequestRejectedException extends RuntimeException {

    protected PaymentRequestRejectedException(String message) {
        super(message);
    }
}

package com.basketry.payment.payment;

/** A missing or invalid field. The message names the field, never its value. */
public class InvalidPaymentRequestException extends PaymentRequestRejectedException {

    public InvalidPaymentRequestException(String message) {
        super(message);
    }
}

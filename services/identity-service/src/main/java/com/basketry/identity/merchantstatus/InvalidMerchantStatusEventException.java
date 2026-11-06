package com.basketry.identity.merchantstatus;

/** A record that can never be applied, however often it is retried. It goes to the dead-letter topic. */
public class InvalidMerchantStatusEventException extends RuntimeException {

    InvalidMerchantStatusEventException(String message) {
        super(message);
    }

    InvalidMerchantStatusEventException(String message, Throwable cause) {
        super(message, cause);
    }
}

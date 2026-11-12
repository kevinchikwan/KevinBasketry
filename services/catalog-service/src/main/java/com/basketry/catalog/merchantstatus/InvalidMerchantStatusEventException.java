package com.basketry.catalog.merchantstatus;

/**
 * A merchant.status.changed record that can never be applied, however often it
 * is retried. It goes to the dead-letter topic. The message never quotes a
 * value and there is never a Jackson cause: the message is a dead-letter header
 * and a log line.
 */
public class InvalidMerchantStatusEventException extends RuntimeException {

    InvalidMerchantStatusEventException(String message) {
        super(message);
    }
}

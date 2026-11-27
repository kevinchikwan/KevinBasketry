package com.basketry.promotion.usage;

/**
 * An order.succeeded record that can never be counted: it goes to the
 * dead-letter topic. The message never quotes a value or a Jackson message, and
 * there is no cause: it goes into a dead-letter header and a log line.
 */
public class InvalidOrderSucceededException extends RuntimeException {

    public InvalidOrderSucceededException(String message) {
        super(message);
    }
}

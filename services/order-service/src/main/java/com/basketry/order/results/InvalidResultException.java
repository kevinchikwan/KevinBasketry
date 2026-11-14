package com.basketry.order.results;

/**
 * A result record that can never be applied: it goes to its dead-letter topic.
 * The message never quotes a value or a Jackson message, and carries no
 * Jackson cause: it goes into a dead-letter header and a log line.
 */
public class InvalidResultException extends RuntimeException {

    public InvalidResultException(String message) {
        super(message);
    }
}

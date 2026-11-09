package com.basketry.inventory.stock;

/**
 * A request that can never succeed, however often it is retried. Nothing is
 * changed and no result is published; the Kafka listener (next stage) sends
 * the record to its dead-letter topic. The message names the field, never
 * quotes the request.
 */
public abstract class StockRequestRejectedException extends RuntimeException {

    protected StockRequestRejectedException(String message) {
        super(message);
    }
}

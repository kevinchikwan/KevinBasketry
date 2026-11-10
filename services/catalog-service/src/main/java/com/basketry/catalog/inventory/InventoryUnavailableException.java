package com.basketry.catalog.inventory;

/** A stock read did not get a usable answer: no connection, a timeout, or any status but 200. */
public class InventoryUnavailableException extends RuntimeException {

    public InventoryUnavailableException(String message) {
        super(message);
    }

    public InventoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

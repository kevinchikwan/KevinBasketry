package com.basketry.catalog.inventory;

/**
 * What inventory-service answered to POST /internal/stock, as the relay needs it.
 *
 * @param detail for the log and the request's {@code last_error}; never the API key
 */
public record StockRowAnswer(Kind kind, String detail) {

    public enum Kind {
        /** 201 CREATED or 200 ALREADY_EXISTS: the row exists for this merchant. */
        DONE,
        /** 409 PRODUCT_OWNED_BY_OTHER_MERCHANT. */
        CONFLICT,
        /** Any other 4xx except 401: the request itself is wrong, and repeating it changes nothing. */
        REJECTED,
        /** 401 (the keys differ or none is set), 5xx, a timeout or no connection: worth trying again later. */
        RETRY
    }
}

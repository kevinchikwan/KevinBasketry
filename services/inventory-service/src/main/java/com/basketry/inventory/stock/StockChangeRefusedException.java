package com.basketry.inventory.stock;

/**
 * An adjustment or a stock-row creation refused because of the stored state.
 * Nothing is changed. {@link #code} is the problem-detail code the REST layer
 * will answer with.
 */
public abstract class StockChangeRefusedException extends RuntimeException {

    private final String code;

    protected StockChangeRefusedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

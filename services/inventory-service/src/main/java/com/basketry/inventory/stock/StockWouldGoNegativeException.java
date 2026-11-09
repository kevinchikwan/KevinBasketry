package com.basketry.inventory.stock;

/** A decrease larger than the stock still available, reservations in progress already taken off. */
public class StockWouldGoNegativeException extends StockChangeRefusedException {

    public StockWouldGoNegativeException(long productId, long quantity, long delta) {
        super("STOCK_WOULD_GO_NEGATIVE", "Product " + productId + " has " + quantity
                + " in stock; a change of " + delta + " would take it below zero");
    }
}

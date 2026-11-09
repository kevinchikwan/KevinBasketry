package com.basketry.inventory.stock;

/** An increase that would take the stock above {@link StockLimits#MAX_STOCK}. */
public class StockLimitExceededException extends StockChangeRefusedException {

    public StockLimitExceededException(long productId, long quantity, long delta) {
        super("STOCK_LIMIT_EXCEEDED", "Product " + productId + " has " + quantity
                + " in stock; a change of " + delta + " would take it above " + StockLimits.MAX_STOCK);
    }
}

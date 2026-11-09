package com.basketry.inventory.stock;

/** No stock row for the product. */
public class UnknownProductException extends StockChangeRefusedException {

    public UnknownProductException(long productId) {
        super("UNKNOWN_PRODUCT", "No stock for product " + productId);
    }
}

package com.basketry.inventory.stock;

/** A stock row for this product already exists, owned by a different merchant. The owner never changes. */
public class ProductOwnedByOtherMerchantException extends StockChangeRefusedException {

    public ProductOwnedByOtherMerchantException(long productId) {
        super("PRODUCT_OWNED_BY_OTHER_MERCHANT", "Product " + productId + " belongs to another merchant");
    }
}

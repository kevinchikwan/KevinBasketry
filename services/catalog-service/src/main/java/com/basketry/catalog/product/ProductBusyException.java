package com.basketry.catalog.product;

/** Another request held the product's row lock for longer than the lock timeout: a 503 PRODUCT_BUSY. */
public class ProductBusyException extends RuntimeException {

    public ProductBusyException(Throwable cause) {
        super("The product is being changed by another request. Try again.", cause);
    }
}

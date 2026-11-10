package com.basketry.catalog.product;

/**
 * The product does not exist or belongs to another merchant: the same answer
 * for both (a 403 once the REST API exists), so a merchant cannot learn which
 * ids exist (FR-M3; DESIGN.md Section 3, as inventory-service).
 */
public class ProductNotOwnedException extends RuntimeException {

    public ProductNotOwnedException() {
        super("This product does not belong to your merchant.");
    }
}

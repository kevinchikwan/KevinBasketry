package com.basketry.catalog.stockrow;

/** Where a product's stock-row creation in inventory-service stands (DESIGN.md Section 2, catalog-service). */
public enum StockRowStatus {
    /** Not created yet; the relay keeps trying. inventory-service has no row, so the product reads as out of stock. */
    PENDING,
    /** inventory-service has the row for this product's merchant (201 CREATED or 200 ALREADY_EXISTS). */
    CREATED,
    /**
     * inventory-service has a row for this product id that belongs to another
     * merchant (409 PRODUCT_OWNED_BY_OTHER_MERCHANT). Final: the product always
     * reads as out of stock, because that row's stock is not this product's.
     */
    CONFLICT,
    /**
     * inventory-service refused the request as invalid (another 4xx): a bug.
     * Final; logged as an error. The product always reads as out of stock, as
     * with CONFLICT.
     */
    REJECTED
}

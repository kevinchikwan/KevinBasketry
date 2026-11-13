package com.basketry.order.checkout;

/**
 * Why a cart line cannot be bought (DESIGN.md Section 2, order-service, cart
 * rules). A line gets the first that applies, in this order.
 */
public enum RefusalReason {
    /** catalog-service does not know the product. */
    NOT_FOUND,
    /** The product belongs to the merchant linked to the buyer's account. */
    OWN_PRODUCT,
    /** The product is deactivated. */
    INACTIVE,
    /** catalog-service's or merchant-service's flag is false, or merchant-service does not know the merchant. */
    MERCHANT_INACTIVE,
    /** The stock row is CONFLICT or REJECTED. */
    STOCK_ROW_UNAVAILABLE
}

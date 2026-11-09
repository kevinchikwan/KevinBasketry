package com.basketry.inventory.stock;

/**
 * One reserve request: {@code order.stock-reserve.requested} without its
 * transport (DESIGN.md Section 5). Validated on construction. A quantity above
 * {@link StockLimits#MAX_RESERVATION_QUANTITY} is valid here: it is answered
 * with QUANTITY_OVER_LIMIT, not rejected.
 */
public record ReserveCommand(long orderId, long productId, int quantity) {

    public ReserveCommand {
        Commands.requirePositive(orderId, "orderId");
        Commands.requirePositive(productId, "productId");
        Commands.requirePositive(quantity, "quantity");
    }
}

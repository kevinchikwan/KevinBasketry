package com.basketry.inventory.stock;

/**
 * One release request: {@code order.stock-release.requested} without its
 * transport (DESIGN.md Section 5). The quantity is only checked against the
 * stored reservation; the stored quantity is what goes back.
 */
public record ReleaseCommand(long orderId, long productId, int quantity) {

    public ReleaseCommand {
        Commands.requirePositive(orderId, "orderId");
        Commands.requirePositive(productId, "productId");
        Commands.requirePositive(quantity, "quantity");
    }
}

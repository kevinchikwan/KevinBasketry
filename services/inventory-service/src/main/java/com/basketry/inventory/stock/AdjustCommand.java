package com.basketry.inventory.stock;

/** A merchant's manual change of a product's stock, as a delta (DESIGN.md Section 2, inventory-service). */
public record AdjustCommand(long productId, long delta) {

    public AdjustCommand {
        Commands.requirePositive(productId, "productId");
        if (delta == 0) {
            throw new InvalidStockRequestException("delta must not be 0");
        }
        if (delta > StockLimits.MAX_ADJUSTMENT || delta < -StockLimits.MAX_ADJUSTMENT) {
            throw new InvalidStockRequestException("delta must be between -" + StockLimits.MAX_ADJUSTMENT
                    + " and " + StockLimits.MAX_ADJUSTMENT);
        }
    }
}

package com.basketry.inventory.stock;

/** Creates the stock row of a product for its owner, starting at zero. */
public record CreateStockCommand(long productId, long merchantId) {

    public CreateStockCommand {
        Commands.requirePositive(productId, "productId");
        Commands.requirePositive(merchantId, "merchantId");
    }
}

package com.basketry.inventory.stock;

final class Commands {

    private Commands() {
    }

    static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new InvalidStockRequestException(field + " must be positive");
        }
    }
}

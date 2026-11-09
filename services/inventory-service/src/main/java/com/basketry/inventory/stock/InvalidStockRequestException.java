package com.basketry.inventory.stock;

/** A missing or invalid field. */
public class InvalidStockRequestException extends StockRequestRejectedException {

    public InvalidStockRequestException(String message) {
        super(message);
    }
}

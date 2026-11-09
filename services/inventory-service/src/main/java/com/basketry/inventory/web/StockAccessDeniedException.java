package com.basketry.inventory.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 403. For a single product it is also the answer when the product does not
 * exist, so a non-admin cannot tell another merchant's product from a missing
 * one; only an admin can get a 404.
 */
public class StockAccessDeniedException extends ErrorResponseException {

    private StockAccessDeniedException(String detail) {
        super(HttpStatus.FORBIDDEN, ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail), null);
    }

    static StockAccessDeniedException view() {
        return new StockAccessDeniedException("Only an admin or the merchant that owns the stock can view it.");
    }

    static StockAccessDeniedException adjust() {
        return new StockAccessDeniedException("Only the merchant that owns this product can adjust its stock.");
    }
}

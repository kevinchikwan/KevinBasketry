package com.basketry.catalog.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 403. For a single product it is also the answer when the product does not
 * exist, so a non-admin cannot tell another merchant's product from a missing
 * one; only an admin can get a 404 (FR-M3; as inventory-service).
 */
public class ProductAccessDeniedException extends ErrorResponseException {

    private ProductAccessDeniedException(String detail) {
        super(HttpStatus.FORBIDDEN, ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail), null);
    }

    static ProductAccessDeniedException write() {
        return new ProductAccessDeniedException("Only a merchant can create or change products, and only its own.");
    }

    static ProductAccessDeniedException view() {
        return new ProductAccessDeniedException("Only an admin or the merchant that owns a product can view it here.");
    }
}

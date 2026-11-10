package com.basketry.catalog.web;

import com.basketry.catalog.product.InvalidProductException;
import com.basketry.catalog.product.ProductBusyException;
import com.basketry.catalog.product.ProductNotOwnedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the service's exceptions, which carry no HTTP types, to problem
 * details. A 503 that a client may need to tell apart carries a {@code code}.
 */
@RestControllerAdvice
class CatalogExceptionHandler {

    /** 400 naming every broken field rule, in {@code violations}. */
    @ExceptionHandler(InvalidProductException.class)
    ProblemDetail invalid(InvalidProductException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setProperty("violations", e.getViolations());
        return problem;
    }

    /** Another merchant's product and a missing one alike (FR-M3). */
    @ExceptionHandler(ProductNotOwnedException.class)
    ProblemDetail notOwned(ProductNotOwnedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /** The row lock stayed taken for more than 5 s; nothing was applied, and the client can retry. */
    @ExceptionHandler(ProductBusyException.class)
    ProblemDetail busy(ProductBusyException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setProperty("code", "PRODUCT_BUSY");
        return problem;
    }
}

package com.basketry.inventory.web;

import com.basketry.inventory.stock.InvalidStockRequestException;
import com.basketry.inventory.stock.StockChangeRefusedException;
import com.basketry.inventory.stock.UnknownProductException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the service's exceptions, which carry no HTTP types (the Kafka listener
 * of the next stage throws the same ones), to problem details:
 * an invalid command is 400, an unknown product 404 (only an admin can reach
 * it; everyone else gets 403 first), and every other refusal 409 with its
 * {@code code}.
 */
@RestControllerAdvice
class StockExceptionHandler {

    @ExceptionHandler(InvalidStockRequestException.class)
    ProblemDetail invalid(InvalidStockRequestException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(UnknownProductException.class)
    ProblemDetail unknown(UnknownProductException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(StockChangeRefusedException.class)
    ProblemDetail refused(StockChangeRefusedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("code", e.code());
        return problem;
    }
}

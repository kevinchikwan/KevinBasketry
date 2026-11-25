package com.basketry.promotion.web;

import com.basketry.promotion.pricing.CouponRefusedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every refusal as a problem detail (DESIGN.md Section 3, promotion-service),
 * the shapes of order-service's handler and inventory-service's 409. A coupon
 * refusal is a 409 with its {@code code}, as inventory-service's
 * POST /internal/stock. No detail ever quotes a value from the request: a body
 * carries a coupon code. Extending ResponseEntityExceptionHandler replaces
 * Spring Boot's own problem-detail handler, so Spring's errors (a wrong content
 * type, a wrong method) are problem details too.
 */
@RestControllerAdvice
class PromotionExceptionHandler extends ResponseEntityExceptionHandler {

    /** A missing body: never Spring's message. JSON itself is read by the controller (UnreadableBodyException). */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return ResponseEntity.badRequest()
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, UnreadableBodyException.UNREADABLE));
    }

    /** A fixed detail and the field path, never Jackson's message. */
    @ExceptionHandler(UnreadableBodyException.class)
    ProblemDetail unreadable(UnreadableBodyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 400 naming every broken rule. */
    @ExceptionHandler(InvalidPriceRequestException.class)
    ProblemDetail invalid(InvalidPriceRequestException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setProperty("violations", e.violations());
        return problem;
    }

    /** 409 with the reason as {@code code}; the detail names the reason, never the coupon code. */
    @ExceptionHandler(CouponRefusedException.class)
    ProblemDetail refused(CouponRefusedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("code", e.reason().name());
        return problem;
    }
}

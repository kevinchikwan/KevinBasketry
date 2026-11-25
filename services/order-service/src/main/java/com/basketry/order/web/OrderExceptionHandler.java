package com.basketry.order.web;

import com.basketry.order.auth.TokenCapabilities.InvalidTokenSubjectException;
import com.basketry.order.checkout.CheckoutRefusedException;
import com.basketry.order.checkout.CheckoutRefusedException.BuyerRefused;
import com.basketry.order.checkout.CheckoutRefusedException.CartNotPurchasable;
import com.basketry.order.checkout.CheckoutRefusedException.IdempotencyKeyReused;
import com.basketry.order.checkout.CheckoutRefusedException.PriceChanged;
import com.basketry.order.checkout.InvalidCheckoutException;
import java.util.Map;
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
 * Every refusal as a problem detail (DESIGN.md Section 3, order-service). A
 * 403, 409 or 503 a client may need to tell apart carries a {@code code}. No
 * detail ever quotes a value from the request: a checkout carries a card
 * number. Extending ResponseEntityExceptionHandler replaces Spring Boot's own
 * problem-detail handler, so Spring's errors (a missing body, a wrong content
 * type) are problem details too.
 */
@RestControllerAdvice
class OrderExceptionHandler extends ResponseEntityExceptionHandler {

    static final String UNREADABLE = "The body is not JSON of the checkout's shape.";

    /**
     * A missing body: the fixed detail, never Spring's message. JSON itself is
     * read by CheckoutController, so Jackson's message, which can quote the
     * input (a card number), never reaches Spring or its log.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, UNREADABLE));
    }

    /** The fixed detail and the field path only (UnreadableBodyException). */
    @ExceptionHandler(UnreadableBodyException.class)
    ProblemDetail unreadable(UnreadableBodyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 400 naming every broken rule; COUPONS_NOT_SUPPORTED when a coupon was sent. */
    @ExceptionHandler(InvalidCheckoutException.class)
    ProblemDetail invalid(InvalidCheckoutException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The checkout breaks " + e.violations().size() + " rule(s).");
        problem.setProperty("violations", e.violations());
        if (e.couponsNotSupported()) {
            problem.setProperty("code", "COUPONS_NOT_SUPPORTED");
        }
        return problem;
    }

    @ExceptionHandler(CheckoutRefusedException.class)
    ProblemDetail refused(CheckoutRefusedException e) {
        HttpStatus status = switch (e) {
            case BuyerRefused buyer -> HttpStatus.FORBIDDEN;
            case IdempotencyKeyReused reused -> HttpStatus.CONFLICT;
            case CartNotPurchasable cart -> HttpStatus.CONFLICT;
            case PriceChanged price -> HttpStatus.CONFLICT;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setProperty("code", e.code());
        if (e instanceof CartNotPurchasable cart) {
            problem.setProperty("lines", cart.lines().stream()
                    .map(line -> Map.of("productId", line.productId(), "reason", line.reason().name())).toList());
        }
        if (e instanceof PriceChanged price) {
            problem.setProperty("lines", price.lines().stream()
                    .map(line -> Map.of("productId", line.productId(), "unitPrice", line.unitPrice())).toList());
            problem.setProperty("total", price.total());
        }
        return problem;
    }

    /** identity-service never issues such a token: it is treated as an invalid one. */
    @ExceptionHandler(InvalidTokenSubjectException.class)
    ResponseEntity<ProblemDetail> invalidSubject(InvalidTokenSubjectException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage()));
    }

    @ExceptionHandler(OrderWebExceptions.BadRequest.class)
    ProblemDetail badRequest(OrderWebExceptions.BadRequest e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(OrderWebExceptions.Forbidden.class)
    ProblemDetail forbidden(OrderWebExceptions.Forbidden e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(OrderWebExceptions.NotFound.class)
    ProblemDetail notFound(OrderWebExceptions.NotFound e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}

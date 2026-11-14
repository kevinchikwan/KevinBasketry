package com.basketry.order.web;

import com.basketry.order.auth.TokenCapabilities;
import com.basketry.order.checkout.CheckoutCommand;
import com.basketry.order.checkout.CheckoutService;
import com.basketry.order.checkout.CheckoutService.Buyer;
import com.basketry.order.checkout.CheckoutService.CheckoutResult;
import com.basketry.order.web.OrderViews.BuyerOrder;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /orders/checkout (FR-B7 to FR-B12; DESIGN.md Section 3, order-service).
 * The order of checks: the token (the chain: 401 or 503; a sub that is not an
 * account is 401), the header and body (400, every rule at once), the BUYER
 * capability (403), then CheckoutService. 202 for a new order, 200 for a
 * replay, both with the buyer's view and Location: /orders/{reference}, a
 * relative URI because behind a gateway this service cannot know its public
 * host.
 */
@RestController
public class CheckoutController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CheckoutService checkout;

    public CheckoutController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    @PostMapping("/orders/checkout")
    public ResponseEntity<BuyerOrder> checkout(@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                               @RequestBody CheckoutRequest body, @AuthenticationPrincipal Jwt jwt) {
        long accountId = TokenCapabilities.accountId(jwt);
        CheckoutCommand command = body.toCommand(key);
        if (!TokenCapabilities.isBuyer(jwt)) {
            throw new OrderWebExceptions.Forbidden("Checking out needs a buyer account.");
        }
        CheckoutResult result = checkout.checkout(command, new Buyer(accountId, jwt.getTokenValue()));
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(URI.create("/orders/" + result.order().getOrderReference()))
                .body(OrderViews.buyer(result.order(), result.charges(), result.lines()));
    }
}

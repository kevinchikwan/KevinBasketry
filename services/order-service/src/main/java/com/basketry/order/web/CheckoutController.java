package com.basketry.order.web;

import com.basketry.order.auth.TokenCapabilities;
import com.basketry.order.checkout.CheckoutCommand;
import com.basketry.order.checkout.CheckoutService;
import com.basketry.order.checkout.CheckoutService.Buyer;
import com.basketry.order.checkout.CheckoutService.CheckoutResult;
import com.basketry.order.web.OrderViews.BuyerOrder;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /orders/checkout (FR-B7 to FR-B12; DESIGN.md Section 3, order-service).
 * The order of checks: the token (the chain: 401 or 503; a sub that is not an
 * account is 401), the header and body (400, every rule at once), the BUYER
 * capability (403), then CheckoutService. 202 for a new order, 200 for a
 * replay, both with the buyer's view and Location: /orders/{reference}, a
 * relative URI because behind a gateway this service cannot know its public
 * host.
 *
 * <p>The body arrives as bytes and is read here, first, with the application's
 * own JsonMapper (every setting of application.yml), as promotion-service's
 * pricing endpoint: when Spring reads it, a Jackson error reaches Spring's
 * DEBUG log with Jackson's message, which quotes the input, a card number
 * included (HttpJacksonMessageLeakTests). Only JSON is accepted, as when
 * Spring's JSON converter read it; anything else is a 415.
 */
@RestController
public class CheckoutController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CheckoutService checkout;
    private final JsonMapper json;

    public CheckoutController(CheckoutService checkout, JsonMapper json) {
        this.checkout = checkout;
        this.json = json;
    }

    @PostMapping(path = "/orders/checkout", consumes = {MediaType.APPLICATION_JSON_VALUE, "application/*+json"})
    public ResponseEntity<BuyerOrder> checkout(@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key,
                                               @RequestBody byte[] bytes, @AuthenticationPrincipal Jwt jwt) {
        CheckoutRequest body = read(bytes);
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

    /** Before the token's subject and the BUYER check, as when Spring read the body before the method ran. */
    private CheckoutRequest read(byte[] bytes) {
        CheckoutRequest body;
        try {
            body = json.readValue(bytes, CheckoutRequest.class);
        } catch (JacksonException unreadable) {
            throw new UnreadableBodyException(unreadable);
        }
        if (body == null) {
            // The JSON literal null: Spring answered it as a missing body.
            throw new UnreadableBodyException();
        }
        return body;
    }
}

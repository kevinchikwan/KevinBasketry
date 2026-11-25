package com.basketry.promotion.web;

import com.basketry.promotion.pricing.CouponPricingService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /internal/coupons/price, for order-service's checkout (DESIGN.md
 * Section 3, promotion-service). Internal key only, in its own security chain.
 * 200 with a discount per merchant; 409 with the refusal's {@code code}; 400
 * for a body that breaks a rule (PromotionExceptionHandler). Nothing is
 * written. The request and answer are pinned by docs/contracts/coupon-pricing.json.
 *
 * <p>The body arrives as bytes and is read here, with the application's own
 * JsonMapper (every setting of application.yml and StrictJsonConfig): when
 * Spring reads it, a Jackson error reaches Spring's DEBUG log with Jackson's
 * message, which quotes the input.
 */
@RestController
public class InternalPricingController {

    static final String PATH = "/internal/coupons/price";

    private final CouponPricingService pricing;
    private final JsonMapper json;

    public InternalPricingController(CouponPricingService pricing, JsonMapper json) {
        this.pricing = pricing;
        this.json = json;
    }

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public PriceResponse price(@RequestBody byte[] body) {
        PriceRequest.Command command = read(body).toCommand();
        return PriceResponse.from(pricing.price(command.code(), command.cart()));
    }

    private PriceRequest read(byte[] body) {
        PriceRequest request;
        try {
            request = json.readValue(body, PriceRequest.class);
        } catch (JacksonException unreadable) {
            throw new UnreadableBodyException(unreadable);
        }
        if (request == null) {
            // The body was the JSON literal null.
            throw new InvalidPriceRequestException(List.of("The body must be a JSON object."));
        }
        return request;
    }
}

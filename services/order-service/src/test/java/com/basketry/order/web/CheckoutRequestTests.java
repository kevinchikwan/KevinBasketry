package com.basketry.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The request object, outside Spring. Until F1 Spring logged it at DEBUG
 * ("Read ... to [...]") and HttpCardNumberLeakTests caught an unmasked
 * toString there; the controller now reads the body itself, so the mask is
 * checked here, should anything ever log the object again.
 */
class CheckoutRequestTests {

    private static final String SENTINEL = "4929117300568841";

    /** Mutation: CheckoutRequest's toString with the card number. */
    @Test
    void toStringMasksTheCardNumber() {
        CheckoutRequest request = new CheckoutRequest(
                List.of(new CheckoutRequest.LineRequest(10L, 1L, Map.of())), null, SENTINEL,
                new BigDecimal("2.50"), Map.of());

        assertThat(request.toString()).contains("cardNumber=***").doesNotContain(SENTINEL)
                .doesNotContain(SENTINEL.substring(0, 12));
    }
}

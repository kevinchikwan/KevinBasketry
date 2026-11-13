package com.basketry.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.order.checkout.CheckoutCommand.CheckoutLine;
import com.basketry.order.config.FingerprintKey;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What makes two checkouts "the same body" for the Idempotency-Key (DESIGN.md
 * Section 2, order-service, the fingerprint).
 */
class RequestFingerprintTests {

    private static final FingerprintKey KEY = new FingerprintKey("test-only-fingerprint-key-not-a-secret");
    private static final String CARD = "4000000000000002";

    private static CheckoutCommand command(List<CheckoutLine> lines, String card, String total) {
        return new CheckoutCommand("key-0001", lines, null, card, new BigDecimal(total));
    }

    private static String fingerprint(CheckoutCommand command) {
        return KEY.hmac(RequestFingerprint.canonical(command)).orElseThrow();
    }

    private static final List<CheckoutLine> CART = List.of(new CheckoutLine(7L, 2L), new CheckoutLine(3L, 1L));

    /**
     * The same cart in another order, and 13.5 for 13.50, is the same checkout.
     * Mutations: the lines not sorted; expectedTotal without setScale(2).
     */
    @Test
    void lineOrderAndTrailingZerosDoNotMatter() {
        String first = fingerprint(command(CART, CARD, "13.50"));

        assertThat(fingerprint(command(List.of(CART.get(1), CART.get(0)), CARD, "13.5"))).isEqualTo(first);
    }

    /**
     * Each part of the body changes it. Mutations: the card left out of the
     * canonical text; the quantity left out; the total left out.
     */
    @Test
    void everyPartOfTheBodyChangesIt() {
        String first = fingerprint(command(CART, CARD, "13.50"));

        assertThat(fingerprint(command(CART, "4000000000000010", "13.50"))).isNotEqualTo(first);
        assertThat(fingerprint(command(List.of(new CheckoutLine(7L, 3L), new CheckoutLine(3L, 1L)), CARD, "13.50")))
                .isNotEqualTo(first);
        assertThat(fingerprint(command(List.of(new CheckoutLine(8L, 2L), new CheckoutLine(3L, 1L)), CARD, "13.50")))
                .isNotEqualTo(first);
        assertThat(fingerprint(command(CART, CARD, "13.51"))).isNotEqualTo(first);
    }

    /**
     * A keyed HMAC, 64 hex digits, never containing the card number: another key
     * gives another fingerprint. Mutation: a plain SHA-256 of the text (the key
     * ignored).
     */
    @Test
    void itIsAKeyedHmac() {
        CheckoutCommand command = command(CART, CARD, "13.50");
        String withOurKey = fingerprint(command);
        String withAnotherKey = new FingerprintKey("another-fingerprint-key-not-a-secret-0")
                .hmac(RequestFingerprint.canonical(command)).orElseThrow();

        assertThat(withOurKey).matches("[0-9a-f]{64}").isNotEqualTo(withAnotherKey);
        assertThat(withOurKey).doesNotContain(CARD.substring(0, 12));
    }
}

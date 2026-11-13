package com.basketry.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.basketry.order.checkout.CheckoutCommand.CheckoutLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checkout's body rules (DESIGN.md Section 3, order-service, POST /orders/checkout). */
class CheckoutCommandTests {

    private static final String KEY = "key-0001";
    private static final String CARD = "4000000000000002";
    private static final String SENTINEL = "4929117300568841";

    private static List<CheckoutLine> lines(int count, long quantity) {
        List<CheckoutLine> lines = new ArrayList<>();
        for (long product = 1; product <= count; product++) {
            lines.add(new CheckoutLine(product, quantity));
        }
        return lines;
    }

    private static CheckoutCommand command(String key, List<CheckoutLine> lines, String coupon, String card,
                                           String total) {
        return new CheckoutCommand(key, lines, coupon, card, total == null ? null : new BigDecimal(total));
    }

    private static InvalidCheckoutException refused(String key, List<CheckoutLine> lines, String coupon, String card,
                                                    String total) {
        return catchThrowableOfType(InvalidCheckoutException.class, () -> command(key, lines, coupon, card, total));
    }

    /** Mutations: MAX_LINES 99 or 101 (100 refused, or 101 accepted). */
    @Test
    void oneToOneHundredLines() {
        assertThat(command(KEY, lines(1, 1), null, CARD, "1.00").lines()).hasSize(1);
        assertThat(command(KEY, lines(100, 1), null, CARD, "1.00").lines()).hasSize(100);
        assertThat(refused(KEY, lines(0, 1), null, CARD, "1.00")).isNotNull();
        assertThat(refused(KEY, lines(101, 1), null, CARD, "1.00")).isNotNull();
        assertThat(refused(KEY, null, null, CARD, "1.00")).isNotNull();
    }

    /** Mutations: {@code < 1} as {@code < 0}; MAX_QUANTITY 1001. */
    @Test
    void aQuantityIsOneToOneThousand() {
        assertThat(command(KEY, lines(1, 1000), null, CARD, "1.00").lines().getFirst().quantity()).isEqualTo(1000);
        assertThat(refused(KEY, lines(1, 0), null, CARD, "1.00").violations())
                .containsExactly("lines[0].quantity must be a whole number from 1 to 1000.");
        assertThat(refused(KEY, lines(1, 1001), null, CARD, "1.00")).isNotNull();
        assertThat(refused(KEY, List.of(new CheckoutLine(1L, null)), null, CARD, "1.00")).isNotNull();
    }

    /** Mutation: the repeat check removed (the second line of a product accepted). */
    @Test
    void aProductTwiceIsRefusedAndNonPositiveIdsToo() {
        assertThat(refused(KEY, List.of(new CheckoutLine(7L, 1L), new CheckoutLine(7L, 2L)), null, CARD, "1.00")
                .violations()).containsExactly("lines[1].productId repeats an earlier line.");
        assertThat(refused(KEY, List.of(new CheckoutLine(0L, 1L)), null, CARD, "1.00")).isNotNull();
        assertThat(refused(KEY, List.of(new CheckoutLine(null, 1L)), null, CARD, "1.00")).isNotNull();
    }

    /** Mutation: the key pattern {7,64} or {8,65}. */
    @ParameterizedTest
    @ValueSource(strings = {"1234567", "abc def gh", "key/0001", ""})
    void aBadIdempotencyKeyIsRefused(String key) {
        assertThat(refused(key, lines(1, 1), null, CARD, "1.00")).isNotNull();
    }

    @Test
    void anIdempotencyKeyOfEightToSixtyFourCharactersIsAccepted() {
        assertThat(command("A-b_1234", lines(1, 1), null, CARD, "1.00").idempotencyKey()).hasSize(8);
        assertThat(command("k".repeat(64), lines(1, 1), null, CARD, "1.00").idempotencyKey()).hasSize(64);
        assertThat(refused("k".repeat(65), lines(1, 1), null, CARD, "1.00")).isNotNull();
        assertThat(refused(null, lines(1, 1), null, CARD, "1.00")).isNotNull();
    }

    /**
     * payment-service's rule: 12 to 19 ASCII digits. Mutations: {12,19} as
     * {11,19} or {12,20}; {@code \\d} with UNICODE_CHARACTER_CLASS (Arabic-Indic
     * digits accepted).
     */
    @ParameterizedTest
    @ValueSource(strings = {"40000000000", "40000000000000000000", "4000 0000 0000 0002", "400000000000000X",
            "٤٠٠٠٠٠٠٠٠٠٠٠"})
    void aBadCardNumberIsRefused(String card) {
        assertThat(refused(KEY, lines(1, 1), null, card, "1.00").violations())
                .containsExactly("cardNumber must be 12 to 19 digits.");
    }

    @Test
    void twelveAndNineteenDigitsAreAccepted() {
        assertThat(command(KEY, lines(1, 1), null, "400000000000", "1.00").cardNumber()).hasSize(12);
        assertThat(command(KEY, lines(1, 1), null, "4000000000000000002", "1.00").cardNumber()).hasSize(19);
    }

    /**
     * Above 0, at most 2 decimal places (trailing zeros do not count), at most
     * 9,999,999,999.99. Mutations: {@code scale()} without stripTrailingZeros
     * (9.990 refused); {@code compareTo(MAX_TOTAL) >= 0}; {@code signum() < 0}.
     */
    @Test
    void theExpectedTotalRules() {
        assertThat(command(KEY, lines(1, 1), null, CARD, "9.990").expectedTotal()).isEqualByComparingTo("9.99");
        assertThat(command(KEY, lines(1, 1), null, CARD, "9999999999.99").expectedTotal()).isNotNull();
        for (String bad : new String[] {"0", "0.00", "-1.00", "0.001", "10000000000.00"}) {
            assertThat(refused(KEY, lines(1, 1), null, CARD, bad)).as(bad).isNotNull();
        }
        assertThat(refused(KEY, lines(1, 1), null, CARD, null)).isNotNull();
    }

    /** Mutation: the coupon check removed, or the flag left false. */
    @Test
    void aCouponIsRefusedAndFlagged() {
        InvalidCheckoutException refused = refused(KEY, lines(1, 1), "SAVE10", CARD, "1.00");

        assertThat(refused.couponsNotSupported()).isTrue();
        assertThat(refused.violations()).containsExactly("couponCode: coupons are not supported yet.");
        assertThat(refused(KEY, lines(1, 1), null, "1", "1.00").couponsNotSupported()).isFalse();
    }

    /** Every broken rule is named at once. Mutation: throw at the first violation. */
    @Test
    void everyBrokenRuleIsNamedAtOnce() {
        InvalidCheckoutException refused = refused("short", List.of(new CheckoutLine(1L, 0L), new CheckoutLine(1L, 1L)),
                "SAVE10", "12", "0");

        assertThat(refused.violations()).hasSize(6);
    }

    /**
     * The card number never reaches a message or toString, and violations quote
     * no value at all. Mutations: the card number in a violation; the record's
     * own toString.
     */
    @Test
    void theCardNumberIsInNoMessageAndNoToString() {
        InvalidCheckoutException refused = refused(KEY, lines(1, 1), null, SENTINEL + "000000", "0");
        assertThat(refused.getMessage()).doesNotContain(SENTINEL.substring(0, 12));
        assertThat(refused.violations()).noneMatch(violation -> violation.contains(SENTINEL.substring(0, 12)));

        assertThat(command(KEY, lines(1, 1), null, SENTINEL, "1.00").toString())
                .doesNotContain(SENTINEL.substring(0, 12))
                .contains("cardNumber=***");
    }

    @Test
    void theLinesAreCopied() {
        List<CheckoutLine> given = lines(2, 1);
        CheckoutCommand command = command(KEY, given, null, CARD, "1.00");
        given.clear();

        assertThat(command.lines()).hasSize(2);
        assertThatThrownBy(() -> command.lines().add(new CheckoutLine(9L, 1L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

package com.basketry.order.checkout;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One checkout as the buyer sent it (DESIGN.md Section 3, order-service,
 * POST /orders/checkout): checked here against every body rule, all at once,
 * before anything else happens (checkout step 1). A broken rule is an
 * {@link InvalidCheckoutException} naming each one; no message ever quotes a
 * value, so the card number cannot reach one, and {@link #toString()} masks it.
 *
 * <p>JSON types and unknown fields are the web layer's to check; this class
 * holds the rules that remain once the body has its shape.
 *
 * @param idempotencyKey the Idempotency-Key header: 8 to 64 of [A-Za-z0-9_-]
 * @param lines          1 to 100, no product twice, each quantity 1 to 1,000
 * @param couponCode     must be null until promotion-service exists
 * @param cardNumber     12 to 19 ASCII digits: payment-service's rule, so a valid checkout never sends a
 *                       charge it would dead-letter
 * @param expectedTotal  the total the buyer was shown: above 0, at most 2 decimal places, at most
 *                       9,999,999,999.99
 */
public record CheckoutCommand(String idempotencyKey, List<CheckoutLine> lines, String couponCode, String cardNumber,
                              BigDecimal expectedTotal) {

    public static final int MAX_LINES = 100;
    public static final int MAX_QUANTITY = 1_000;
    static final BigDecimal MAX_TOTAL = new BigDecimal("9999999999.99");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Pattern CARD_NUMBER = Pattern.compile("[0-9]{12,19}");

    public CheckoutCommand {
        List<String> violations = new ArrayList<>();
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            violations.add("Idempotency-Key must be 8 to 64 characters of A-Z, a-z, 0-9, _ and -.");
        }
        checkLines(lines, violations);
        boolean couponGiven = couponCode != null;
        if (couponGiven) {
            violations.add("couponCode: coupons are not supported yet.");
        }
        if (cardNumber == null || !CARD_NUMBER.matcher(cardNumber).matches()) {
            violations.add("cardNumber must be 12 to 19 digits.");
        }
        if (expectedTotal == null || expectedTotal.signum() <= 0
                || expectedTotal.stripTrailingZeros().scale() > 2 || expectedTotal.compareTo(MAX_TOTAL) > 0) {
            violations.add("expectedTotal must be above 0, with at most 2 decimal places, at most "
                    + MAX_TOTAL.toPlainString() + ".");
        }
        if (!violations.isEmpty()) {
            throw new InvalidCheckoutException(violations, couponGiven);
        }
        lines = List.copyOf(lines);
    }

    private static void checkLines(List<CheckoutLine> lines, List<String> violations) {
        if (lines == null || lines.isEmpty() || lines.size() > MAX_LINES) {
            violations.add("lines must have 1 to " + MAX_LINES + " lines.");
            return;
        }
        Set<Long> seen = new HashSet<>();
        for (int index = 0; index < lines.size(); index++) {
            CheckoutLine line = lines.get(index);
            if (line == null) {
                violations.add("lines[" + index + "] is required.");
                continue;
            }
            if (line.productId() == null || line.productId() <= 0) {
                violations.add("lines[" + index + "].productId must be a positive whole number.");
            } else if (!seen.add(line.productId())) {
                violations.add("lines[" + index + "].productId repeats an earlier line.");
            }
            if (line.quantity() == null || line.quantity() < 1 || line.quantity() > MAX_QUANTITY) {
                violations.add("lines[" + index + "].quantity must be a whole number from 1 to " + MAX_QUANTITY + ".");
            }
        }
    }

    /** The card number masked; it is never logged. */
    @Override
    public String toString() {
        return "CheckoutCommand[idempotencyKey=" + idempotencyKey + ", lines=" + lines + ", couponCode=" + couponCode
                + ", cardNumber=***, expectedTotal=" + expectedTotal + "]";
    }

    /** {@code Long} for both, so a missing or out-of-range value can be named rather than fail elsewhere. */
    public record CheckoutLine(Long productId, Long quantity) {
    }
}

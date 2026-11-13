package com.basketry.order.checkout;

import com.basketry.order.checkout.CheckoutCommand.CheckoutLine;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * The canonical text of a checkout's body, which the fingerprint key's HMAC
 * covers (DESIGN.md Section 2, order-service): the lines sorted by productId,
 * the coupon code, the card number and expectedTotal at scale 2. The same cart
 * in another line order, or 13.5 for 13.50, is the same checkout. Never logged
 * or stored: only its HMAC is.
 */
final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static String canonical(CheckoutCommand command) {
        String lines = command.lines().stream()
                .sorted(Comparator.comparing(CheckoutLine::productId))
                .map(line -> line.productId() + ":" + line.quantity())
                .collect(Collectors.joining(","));
        // "-" for no coupon, "=" and the code otherwise, so no code can look like "none".
        String coupon = command.couponCode() == null ? "-" : "=" + command.couponCode();
        return "v1\nlines=" + lines
                + "\ncoupon" + coupon
                + "\ncard=" + command.cardNumber()
                + "\nexpectedTotal=" + command.expectedTotal().setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}

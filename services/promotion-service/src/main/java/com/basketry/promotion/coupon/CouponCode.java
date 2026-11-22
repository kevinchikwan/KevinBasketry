package com.basketry.promotion.coupon;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A normalised coupon code (DESIGN.md Section 2, promotion-service): 4 to 32
 * characters of {@code A-Z 0-9 _ -}. A value of this type is always normalised,
 * so a code is case-insensitive by construction.
 *
 * <p>{@link #normalise} is the rule order-service copies (ROADMAP A9): strip
 * (Unicode whitespace, {@link String#strip()}, not {@link String#trim()}), then
 * refuse any character outside ASCII letters, digits, {@code _} and {@code -},
 * then upper-case with {@link Locale#ROOT}. Checking before upper-casing refuses
 * the characters that upper-case into ASCII ({@code ſ} to S, {@code ß} to SS,
 * {@code ﬁ} to FI, {@code ı} to I). Both services test it against
 * docs/coupon-code-vectors.txt, so the copies cannot drift apart unseen.
 */
public record CouponCode(String value) {

    public static final int MIN_LENGTH = 4;
    public static final int MAX_LENGTH = 32;

    private static final Pattern NORMALISED =
            Pattern.compile("[A-Z0-9_-]{" + MIN_LENGTH + "," + MAX_LENGTH + "}");
    private static final Pattern INPUT_CHARACTERS = Pattern.compile("[A-Za-z0-9_-]*");

    /** Refuses a value that is not already normalised; {@link #normalise} is for what a person typed. */
    public CouponCode {
        if (value == null || !NORMALISED.matcher(value).matches()) {
            throw new InvalidCouponException("A coupon code must be " + MIN_LENGTH + " to " + MAX_LENGTH
                    + " characters of A-Z, 0-9, _ and -.");
        }
    }

    /** The code as typed: stripped, checked for ASCII, upper-cased with Locale.ROOT, then checked. */
    public static CouponCode normalise(String raw) {
        String stripped = raw == null ? "" : raw.strip();
        if (!INPUT_CHARACTERS.matcher(stripped).matches()) {
            throw new InvalidCouponException("A coupon code must be " + MIN_LENGTH + " to " + MAX_LENGTH
                    + " characters of A-Z, 0-9, _ and -.");
        }
        return new CouponCode(stripped.toUpperCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return value;
    }
}

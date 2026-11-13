package com.basketry.order.order;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * {@code ORD-} and 20 random base32 characters (RFC 4648 alphabet, A-Z and
 * 2-7): 100 bits from SecureRandom, so a reference says nothing about order
 * volume and cannot be guessed from another (DESIGN.md Section 2,
 * order-service). The only id the API shows.
 */
public final class OrderReferences {

    public static final Pattern FORMAT = Pattern.compile("ORD-[A-Z2-7]{20}");

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int LENGTH = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    private OrderReferences() {
    }

    public static String next() {
        StringBuilder reference = new StringBuilder("ORD-");
        for (int i = 0; i < LENGTH; i++) {
            reference.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return reference.toString();
    }
}

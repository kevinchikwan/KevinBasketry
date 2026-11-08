package com.basketry.payment.payment;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Transaction references: {@code TXN-} and 32 lowercase hex digits from a
 * random UUID (122 random bits from SecureRandom), so a reference cannot be
 * guessed from an order. Made once, when the row is inserted; a repeated
 * request reads the stored one, so it never changes.
 */
public final class TransactionRefs {

    private static final Pattern FORMAT = Pattern.compile("TXN-[0-9a-f]{32}");

    private TransactionRefs() {
    }

    public static String newRef() {
        return "TXN-" + UUID.randomUUID().toString().replace("-", "");
    }

    public static boolean isWellFormed(String ref) {
        return ref != null && FORMAT.matcher(ref).matches();
    }
}

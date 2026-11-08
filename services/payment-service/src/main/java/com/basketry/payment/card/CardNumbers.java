package com.basketry.payment.card;

/**
 * Rules for the raw card number the charge request carries (DESIGN.md Section
 * 2, payment-service). The full number is used only to look up the mock
 * result: it is never stored, logged or put in an exception message. Only
 * {@link #lastFour} and {@link #mask} may leave this process.
 */
public final class CardNumbers {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 19;

    private CardNumbers() {
    }

    /**
     * 12 to 19 ASCII digits. No Luhn check: the mock approves any number not in
     * its table, and a Luhn decline would be a result the table does not have.
     * {@link Character#isDigit} is not used, because it accepts non-ASCII digits.
     */
    public static boolean isWellFormed(String number) {
        if (number == null || number.length() < MIN_LENGTH || number.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Only for a well-formed number. */
    public static String lastFour(String number) {
        return number.substring(number.length() - 4);
    }

    /** Every digit but the last 4 replaced by '*'. Only for a well-formed number. */
    public static String mask(String number) {
        return "*".repeat(number.length() - 4) + lastFour(number);
    }
}

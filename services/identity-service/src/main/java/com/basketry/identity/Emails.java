package com.basketry.identity;

import java.util.Locale;

/**
 * Email is case-insensitive across the platform (DESIGN.md Section 2): every
 * email is normalized to lowercase before it is stored or compared.
 */
public final class Emails {

    private Emails() {
    }

    public static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}

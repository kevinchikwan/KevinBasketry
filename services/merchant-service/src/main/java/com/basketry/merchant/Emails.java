package com.basketry.merchant;

import java.util.Locale;

/**
 * Email is case-insensitive across the platform (DESIGN.md Section 2): every
 * email is normalized to lowercase before it is stored or compared. Copied
 * from identity-service, which applies the same rule to the login itself.
 */
public final class Emails {

    private Emails() {
    }

    public static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}

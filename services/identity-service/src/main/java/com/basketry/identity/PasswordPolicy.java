package com.basketry.identity;

import java.util.ArrayList;
import java.util.List;

/**
 * The password policy (REQUIREMENTS.md Section 4), applied wherever the
 * application sets a password. Login does not re-check it.
 *
 * <p>Letters and digits are ASCII only, so this matches the front-end's
 * checklist exactly. Length counts code points, not UTF-16 units. 15 code
 * points are at most 60 bytes of UTF-8, which stays inside BCrypt's 72-byte
 * input limit.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 15;
    public static final String SPECIAL_CHARACTERS = "!@#$%^&*()-_=+";

    private PasswordPolicy() {
    }

    /** Every rule the password fails, as user-facing sentences; empty if it is acceptable. */
    public static List<String> violations(String password) {
        List<String> violations = new ArrayList<>();
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH) {
            violations.add("Password must be at least " + MIN_LENGTH + " characters.");
        }
        if (length > MAX_LENGTH) {
            violations.add("Password must be at most " + MAX_LENGTH + " characters.");
        }
        if (password.chars().noneMatch(c -> c >= 'A' && c <= 'Z')) {
            violations.add("Password must contain an uppercase letter (A-Z).");
        }
        if (password.chars().noneMatch(c -> c >= 'a' && c <= 'z')) {
            violations.add("Password must contain a lowercase letter (a-z).");
        }
        if (password.chars().noneMatch(c -> c >= '0' && c <= '9')) {
            violations.add("Password must contain a digit (0-9).");
        }
        if (password.chars().noneMatch(c -> SPECIAL_CHARACTERS.indexOf(c) >= 0)) {
            violations.add("Password must contain a special character from " + SPECIAL_CHARACTERS + ".");
        }
        return violations;
    }
}

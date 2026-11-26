package com.basketry.merchant.merchant;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which unknown field names a 400 may name: order-service's rule. A name is
 * shown only if it starts with a letter, has at most 41 letters, digits and
 * _, and has no run of 6 or more digits; any other is only counted, so a
 * value sent as a field name (a password, a card number) never comes back in
 * an answer or a log line.
 */
final class UnknownFieldNames {

    private static final Pattern SHOWN = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,40}");
    private static final Pattern DIGIT_RUN = Pattern.compile("[0-9]{6,}");

    private UnknownFieldNames() {
    }

    static boolean shown(String name) {
        return SHOWN.matcher(name).matches() && !DIGIT_RUN.matcher(name).find();
    }

    /** The names that may be shown, in the order given. */
    static List<String> shownOf(Collection<String> names) {
        return names.stream().filter(UnknownFieldNames::shown).toList();
    }

    static long hiddenOf(Collection<String> names) {
        return names.stream().filter(name -> !shown(name)).count();
    }
}

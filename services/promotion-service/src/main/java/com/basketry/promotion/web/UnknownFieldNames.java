package com.basketry.promotion.web;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Which unknown field names a coupon endpoint's 400 may name: order-service's
 * rule. A name is shown only if it starts with a letter, has at most 41
 * letters, digits and _, and has no run of 6 or more digits; any other is
 * only counted, so a value sent as a field name never comes back in an answer
 * or a log line. The pricing endpoint names none (PriceRequest).
 */
final class UnknownFieldNames {

    private static final Pattern SHOWN = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,40}");
    private static final Pattern DIGIT_RUN = Pattern.compile("[0-9]{6,}");

    private UnknownFieldNames() {
    }

    static boolean shown(String name) {
        return SHOWN.matcher(name).matches() && !DIGIT_RUN.matcher(name).find();
    }

    /** The shown names sorted in one violation, then the count of the others in another. */
    static void violations(Map<String, Object> fields, List<String> violations) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        TreeSet<String> named = new TreeSet<>();
        int unnamed = 0;
        for (String name : fields.keySet()) {
            if (shown(name)) {
                named.add(name);
            } else {
                unnamed++;
            }
        }
        if (!named.isEmpty()) {
            violations.add("Unknown field(s): " + String.join(", ", named) + ".");
        }
        if (unnamed > 0) {
            violations.add(unnamed + " unknown field(s) at the top level, with a name that is not shown.");
        }
    }
}

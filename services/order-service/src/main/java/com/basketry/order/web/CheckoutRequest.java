package com.basketry.order.web;

import com.basketry.order.checkout.CheckoutCommand;
import com.basketry.order.checkout.CheckoutCommand.CheckoutLine;
import com.basketry.order.checkout.InvalidCheckoutException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Body of POST /orders/checkout (DESIGN.md Section 3, order-service). Each
 * field takes only its own JSON type: {@code expectedTotal} and the line ids a
 * number (the global Jackson settings), {@code cardNumber} and
 * {@code couponCode} a string (the strict deserializer). Any other field, at
 * the top or in a line, is a 400 naming it, together with every rule of
 * {@link CheckoutCommand}. A field is named only if its name starts with a
 * letter, has at most 41 letters, digits and _, and has no run of 6 or more
 * digits; any other is counted with its path, so no client-sent text, such as
 * a card number used as a key (alone or after a letter), is ever echoed.
 * {@link #toString()} masks the card number: Spring logs
 * the body object at DEBUG.
 */
public record CheckoutRequest(List<LineRequest> lines,
                              @JsonDeserialize(using = StrictStringDeserializer.class) String couponCode,
                              @JsonDeserialize(using = StrictStringDeserializer.class) String cardNumber,
                              BigDecimal expectedTotal,
                              @JsonAnySetter Map<String, Object> unknownFields) {

    /** A name that may be echoed: a letter first, at most 41 characters of letters, digits and _. */
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,40}");
    /** Six digits in a row could be part of a card number: such a name is never echoed. */
    private static final Pattern DIGIT_RUN = Pattern.compile("[0-9]{6,}");

    public CheckoutRequest {
        unknownFields = unknownFields == null ? new LinkedHashMap<>() : unknownFields;
    }

    /**
     * Every rule of the header and the body, at once (checkout step 1).
     *
     * @throws InvalidCheckoutException naming every broken rule
     */
    CheckoutCommand toCommand(String idempotencyKey) {
        List<String> violations = new ArrayList<>();
        unknown("", unknownFields, violations);
        List<CheckoutLine> commandLines = null;
        if (lines != null) {
            commandLines = new ArrayList<>();
            for (int index = 0; index < lines.size(); index++) {
                LineRequest line = lines.get(index);
                if (line == null) {
                    commandLines.add(null);
                } else {
                    unknown("lines[" + index + "].", line.unknownFields(), violations);
                    commandLines.add(new CheckoutLine(line.productId(), line.quantity()));
                }
            }
        }
        CheckoutCommand command = null;
        InvalidCheckoutException commandRules = null;
        try {
            command = new CheckoutCommand(idempotencyKey, commandLines, couponCode, cardNumber, expectedTotal);
        } catch (InvalidCheckoutException invalid) {
            commandRules = invalid;
        }
        if (violations.isEmpty() && commandRules == null) {
            return command;
        }
        if (commandRules != null) {
            violations.addAll(commandRules.violations());
        }
        throw new InvalidCheckoutException(violations, commandRules != null && commandRules.couponsNotSupported());
    }

    private static void unknown(String prefix, Map<String, Object> fields, List<String> violations) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        TreeSet<String> named = new TreeSet<>();
        int unnamed = 0;
        for (String name : fields.keySet()) {
            if (FIELD_NAME.matcher(name).matches() && !DIGIT_RUN.matcher(name).find()) {
                named.add(prefix + name);
            } else {
                unnamed++;
            }
        }
        if (!named.isEmpty()) {
            violations.add("Unknown field(s): " + String.join(", ", named) + ".");
        }
        if (unnamed > 0) {
            String where = prefix.isEmpty() ? "the top level" : prefix.substring(0, prefix.length() - 1);
            violations.add(unnamed + " unknown field(s) at " + where + ", with a name that is not shown.");
        }
    }

    /** The card number masked; it is never logged. */
    @Override
    public String toString() {
        return "CheckoutRequest[lines=" + lines + ", couponCode=" + couponCode + ", cardNumber=***, expectedTotal="
                + expectedTotal + "]";
    }

    /** One cart line; {@code Long} so a missing or out-of-range value is named, never defaulted. */
    public record LineRequest(Long productId, Long quantity, @JsonAnySetter Map<String, Object> unknownFields) {

        public LineRequest {
            unknownFields = unknownFields == null ? new LinkedHashMap<>() : unknownFields;
        }

        @Override
        public String toString() {
            return "LineRequest[productId=" + productId + ", quantity=" + quantity + "]";
        }
    }
}

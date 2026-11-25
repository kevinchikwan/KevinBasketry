package com.basketry.promotion.web;

import com.basketry.promotion.coupon.CouponCode;
import com.basketry.promotion.coupon.InvalidCouponException;
import com.basketry.promotion.pricing.MerchantSubtotal;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Body of POST /internal/coupons/price (DESIGN.md Section 3,
 * promotion-service). {@code code} takes only a JSON string (the strict
 * deserializer), the ids and subtotals only JSON numbers (application.yml).
 * Every rule is checked here, all at once, before any coupon is looked up, so
 * a bad body is a 400 whatever the code. Unknown fields are counted with their
 * path but never named: a field name could be a coupon code. No message ever
 * quotes a value sent, and {@link #toString()} shows neither the code nor a
 * subtotal: Spring logs the body object at DEBUG.
 */
public record PriceRequest(@JsonDeserialize(using = StrictStringDeserializer.class) String code,
                           List<MerchantLine> merchants,
                           @JsonAnySetter Map<String, Object> unknownFields) {

    static final int MAX_MERCHANTS = 100;
    /** payment-service's ChargeCommand.MAX_AMOUNT and order-service's CheckoutCommand.MAX_TOTAL. */
    static final BigDecimal MAX_SUBTOTAL = new BigDecimal("9999999999.99");

    public PriceRequest {
        unknownFields = unknownFields == null ? new LinkedHashMap<>() : unknownFields;
    }

    /** What the pricing service takes, once every rule holds. */
    record Command(CouponCode code, List<MerchantSubtotal> cart) {
    }

    /**
     * @throws InvalidPriceRequestException naming every broken rule
     */
    Command toCommand() {
        List<String> violations = new ArrayList<>();
        unknown("the top level", unknownFields, violations);

        CouponCode couponCode = null;
        if (code == null) {
            violations.add("code is required.");
        } else {
            try {
                couponCode = CouponCode.normalise(code);
            } catch (InvalidCouponException invalid) {
                violations.add("code: " + invalid.getMessage());
            }
        }

        List<MerchantSubtotal> cart = new ArrayList<>();
        if (merchants == null || merchants.isEmpty() || merchants.size() > MAX_MERCHANTS) {
            violations.add("merchants must hold 1 to " + MAX_MERCHANTS + " merchants.");
        } else {
            Set<Long> seen = new HashSet<>();
            for (int index = 0; index < merchants.size(); index++) {
                String at = "merchants[" + index + "]";
                MerchantLine line = merchants.get(index);
                if (line == null) {
                    violations.add(at + " is empty.");
                    continue;
                }
                unknown(at, line.unknownFields(), violations);
                boolean valid = true;
                if (line.merchantId() == null || line.merchantId() <= 0) {
                    violations.add(at + ".merchantId must be a whole number above 0.");
                    valid = false;
                } else if (!seen.add(line.merchantId())) {
                    violations.add(at + ".merchantId repeats an earlier merchant.");
                    valid = false;
                }
                if (!validSubtotal(line.subtotal())) {
                    violations.add(at + ".subtotal must be above 0, with at most 2 decimal places, at most "
                            + MAX_SUBTOTAL.toPlainString() + ".");
                    valid = false;
                }
                if (valid) {
                    cart.add(new MerchantSubtotal(line.merchantId(), line.subtotal()));
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new InvalidPriceRequestException(violations);
        }
        return new Command(couponCode, cart);
    }

    private static boolean validSubtotal(BigDecimal subtotal) {
        return subtotal != null && subtotal.signum() > 0 && subtotal.stripTrailingZeros().scale() <= 2
                && subtotal.compareTo(MAX_SUBTOTAL) <= 0;
    }

    private static void unknown(String where, Map<String, Object> fields, List<String> violations) {
        if (fields != null && !fields.isEmpty()) {
            violations.add(fields.size() + " unknown field(s) at " + where + ", with a name that is not shown.");
        }
    }

    /** Neither the code nor a subtotal: Spring logs the body object at DEBUG. */
    @Override
    public String toString() {
        return "PriceRequest[code=***, merchants=" + (merchants == null ? "null" : merchants.size()) + "]";
    }

    /** One merchant of the cart; boxed so a missing value is named, never defaulted. */
    public record MerchantLine(Long merchantId, BigDecimal subtotal,
                               @JsonAnySetter Map<String, Object> unknownFields) {

        public MerchantLine {
            unknownFields = unknownFields == null ? new LinkedHashMap<>() : unknownFields;
        }

        @Override
        public String toString() {
            return "MerchantLine[merchantId=" + merchantId + ", subtotal=***]";
        }
    }
}

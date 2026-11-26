package com.basketry.promotion.web;

import com.basketry.promotion.coupon.CouponCode;
import com.basketry.promotion.coupon.CouponScope;
import com.basketry.promotion.coupon.Discount;
import com.basketry.promotion.coupon.DiscountType;
import com.basketry.promotion.coupon.InvalidCouponException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Body of POST /coupons (DESIGN.md Section 3, promotion-service). The strings
 * take only a JSON string (the strict deserializer), {@code merchantId} and
 * {@code discountValue} only a JSON number (application.yml). Every rule is
 * checked here, all at once, in the order of the fields; no message quotes a
 * value sent. Who may create which scope is the controller's (a 403), once
 * the body holds: only Admin must give the merchant id of a merchant coupon,
 * as a merchant's own comes from its token.
 */
public record CreateCouponRequest(@JsonDeserialize(using = StrictStringDeserializer.class) String code,
                                  @JsonDeserialize(using = StrictStringDeserializer.class) String scope,
                                  Long merchantId,
                                  @JsonDeserialize(using = StrictStringDeserializer.class) String discountType,
                                  BigDecimal discountValue,
                                  @JsonDeserialize(using = StrictStringDeserializer.class) String expiresAt,
                                  @JsonAnySetter Map<String, Object> unknownFields) {

    static final String UNREADABLE = "The body is not JSON of the coupon request's shape.";
    /** The request's own field names, the only names an unreadable body's path may show. */
    static final Set<String> FIELD_NAMES =
            Set.of("code", "scope", "merchantId", "discountType", "discountValue", "expiresAt");
    /** How far ahead a coupon may expire. */
    static final int MAX_YEARS_AHEAD = 2;

    public CreateCouponRequest {
        unknownFields = unknownFields == null ? new LinkedHashMap<>() : unknownFields;
    }

    /** The body once every rule holds; {@code merchantId} is null unless it was sent. */
    record Valid(CouponCode code, CouponScope scope, Long merchantId, Discount discount, Instant expiresAt) {
    }

    /**
     * @param now   the service clock: the expiry must be after it and at most 2 years ahead
     * @param admin whether Admin sends it (Admin must give a merchant coupon's merchant id)
     * @throws InvalidCouponRequestException naming every broken rule
     */
    Valid validate(Instant now, boolean admin) {
        List<String> violations = new ArrayList<>();
        UnknownFieldNames.violations(unknownFields, violations);

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

        CouponScope couponScope = null;
        if (scope == null) {
            violations.add("scope is required.");
        } else if (scope.equals("MERCHANT") || scope.equals("PLATFORM")) {
            couponScope = CouponScope.valueOf(scope);
        } else {
            violations.add("scope must be MERCHANT or PLATFORM.");
        }

        if (merchantId != null && merchantId <= 0) {
            violations.add("merchantId must be a whole number above 0.");
        } else if (couponScope == CouponScope.PLATFORM && merchantId != null) {
            violations.add("merchantId is only for a MERCHANT coupon.");
        } else if (couponScope == CouponScope.MERCHANT && merchantId == null && admin) {
            violations.add("merchantId is required when Admin creates a MERCHANT coupon.");
        }

        DiscountType type = null;
        if (discountType == null) {
            violations.add("discountType is required.");
        } else if (discountType.equals("PERCENTAGE") || discountType.equals("FIXED")) {
            type = DiscountType.valueOf(discountType);
        } else {
            violations.add("discountType must be PERCENTAGE or FIXED.");
        }

        Discount discount = null;
        if (discountValue == null) {
            violations.add("discountValue is required.");
        } else if (type != null) {
            try {
                discount = new Discount(type, discountValue);
            } catch (InvalidCouponException invalid) {
                violations.add("discountValue: " + invalid.getMessage());
            }
        }

        Instant expiry = null;
        if (expiresAt == null) {
            violations.add("expiresAt is required.");
        } else {
            expiry = expiry(expiresAt, now, violations);
        }

        if (!violations.isEmpty()) {
            throw new InvalidCouponRequestException(violations);
        }
        return new Valid(couponCode, couponScope, merchantId, discount, expiry);
    }

    /**
     * An instant with Z or an offset, kept to microseconds as the coupon keeps
     * it, then checked: after now (a coupon expiring now is already expired)
     * and at most 2 years ahead.
     */
    private static Instant expiry(String text, Instant now, List<String> violations) {
        Instant parsed;
        try {
            parsed = OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
                    .truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException | ArithmeticException unparsable) {
            violations.add("expiresAt must be an ISO-8601 instant with Z or an offset, such as 2027-01-31T23:59:59Z.");
            return null;
        }
        Instant latest = now.atOffset(ZoneOffset.UTC).plusYears(MAX_YEARS_AHEAD).toInstant();
        if (!parsed.isAfter(now) || parsed.isAfter(latest)) {
            violations.add("expiresAt must be after now and at most " + MAX_YEARS_AHEAD + " years ahead.");
            return null;
        }
        return parsed;
    }

    /** The unknown fields as a count only: their names and values could be anything. */
    @Override
    public String toString() {
        return "CreateCouponRequest[code=" + code + ", scope=" + scope + ", merchantId=" + merchantId
                + ", discountType=" + discountType + ", discountValue=" + discountValue + ", expiresAt=" + expiresAt
                + ", unknownFields=" + unknownFields.size() + "]";
    }
}

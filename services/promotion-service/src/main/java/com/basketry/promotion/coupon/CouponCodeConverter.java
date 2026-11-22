package com.basketry.promotion.coupon;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores a {@link CouponCode} as its text. Reading a row builds the code again,
 * so a code changed by hand into one that breaks the rule fails on load rather
 * than reaching pricing.
 */
@Converter
class CouponCodeConverter implements AttributeConverter<CouponCode, String> {

    @Override
    public String convertToDatabaseColumn(CouponCode code) {
        return code == null ? null : code.value();
    }

    @Override
    public CouponCode convertToEntityAttribute(String value) {
        return value == null ? null : new CouponCode(value);
    }
}

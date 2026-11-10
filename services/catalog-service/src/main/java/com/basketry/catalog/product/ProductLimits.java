package com.basketry.catalog.product;

import java.math.BigDecimal;

/**
 * The bounds of a product's fields (DESIGN.md Section 2, catalog-service),
 * enforced by the application. Lengths are in UTF-16 code units, as bean
 * validation's {@code @Size} counts them in merchant-service, so a value within
 * them always fits its VARCHAR column on H2 and PostgreSQL.
 */
public final class ProductLimits {

    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_DESCRIPTION_LENGTH = 2000;
    public static final int MAX_CATEGORY_LENGTH = 50;
    public static final BigDecimal MIN_PRICE = new BigDecimal("0.01");
    /** Far below payment-service's amount column even at 1,000 units a line. */
    public static final BigDecimal MAX_PRICE = new BigDecimal("99999.99");
    public static final int PRICE_SCALE = 2;

    private ProductLimits() {
    }
}

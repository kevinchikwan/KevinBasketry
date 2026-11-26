package com.basketry.promotion.coupon;

import java.sql.SQLException;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Creates coupons for the coupon endpoints (ROADMAP A7). There is no lookup
 * before the insert: two creates with the same code could both pass it. The
 * unique constraint on the normalised code decides, and its violation is
 * the 409, so a race between two creates is one 201 and one 409, never a
 * 500 (CouponCreateRaceTests, on PostgreSQL).
 */
@Service
public class CouponManagement {

    /** The constraint in V1__create_coupon_table.sql. */
    static final String UNIQUE_CODE = "uq_coupon_code";

    private final CouponRepository coupons;

    public CouponManagement(CouponRepository coupons) {
        this.coupons = coupons;
    }

    /**
     * @throws CouponCodeTakenException when another coupon has the code
     */
    public Coupon create(Coupon coupon) {
        try {
            // Flushed here, so the insert, and its violation, happen in this call.
            return coupons.saveAndFlush(coupon);
        } catch (DataIntegrityViolationException violation) {
            if (isTheUniqueCode(violation)) {
                throw new CouponCodeTakenException();
            }
            throw violation;
        }
    }

    /** Only the unique code: any other violation is a bug, and stays a 500. */
    private static boolean isTheUniqueCode(DataIntegrityViolationException violation) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
                    && constraint.getConstraintName() != null
                    && constraint.getConstraintName().toLowerCase(Locale.ROOT).contains(UNIQUE_CODE)) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getMessage() != null
                    && sql.getMessage().toLowerCase(Locale.ROOT).contains(UNIQUE_CODE)) {
                return true;
            }
        }
        return false;
    }
}

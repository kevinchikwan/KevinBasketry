package com.basketry.promotion.usage;

import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.coupon.CouponRepository;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The usage count (DESIGN.md Section 2, promotion-service): one coupon_usage
 * row per order.succeeded event that carried a coupon's code, its eventId the
 * key. Counted whatever the coupon is now (inactive, expired): the order
 * succeeded with it. The count never affects pricing.
 *
 * <p>Its own transaction, never the caller's: the listener calls it outside
 * any (TransactionBoundaryTests). There is no lookup of the event before the
 * insert, which two deliveries of one event could both pass: the primary key
 * decides, and its violation, caught after the transaction has rolled back, is
 * a repeat (CouponUsageRaceTests, on PostgreSQL).
 */
@Service
public class CouponUsageService {

    /** The constraint in V2__create_coupon_usage_table.sql. */
    static final String PRIMARY_KEY = "pk_coupon_usage";

    private static final Logger log = LoggerFactory.getLogger(CouponUsageService.class);

    public enum Outcome { COUNTED, ALREADY_COUNTED, NO_COUPON, UNKNOWN_COUPON }

    private final CouponRepository coupons;
    private final CouponUsageRepository usages;
    private final TransactionTemplate transaction;

    public CouponUsageService(CouponRepository coupons, CouponUsageRepository usages,
                              PlatformTransactionManager transactions) {
        this.coupons = coupons;
        this.usages = usages;
        this.transaction = new TransactionTemplate(transactions);
    }

    /**
     * @throws CouponUsageNotRecordedException when the insert breaks a constraint other than the primary key
     */
    public Outcome record(OrderSucceeded event) {
        if (event.couponCode() == null) {
            return Outcome.NO_COUPON;
        }
        try {
            return transaction.execute(status -> {
                Optional<Coupon> coupon = coupons.findByCode(event.couponCode());
                if (coupon.isEmpty()) {
                    // Never the code (pricing's rule): only the ids, to find the event again.
                    log.warn("Event {} (orderId {}) carries a coupon code that no coupon has: skipped, not counted."
                            + " A coupon is never deleted, so promotion-service has lost its data (an H2 restart?)",
                            event.eventId(), event.orderId());
                    return Outcome.UNKNOWN_COUPON;
                }
                usages.saveAndFlush(new CouponUsage(event.eventId(), coupon.get().getId(), event.orderId(),
                        event.occurredAt()));
                log.debug("Event {} (orderId {}) counted for coupon {}", event.eventId(), event.orderId(),
                        coupon.get().getId());
                return Outcome.COUNTED;
            });
        } catch (DataIntegrityViolationException violation) {
            if (isThePrimaryKey(violation)) {
                log.debug("Event {} (orderId {}) was already counted", event.eventId(), event.orderId());
                return Outcome.ALREADY_COUNTED;
            }
            log.error("Event {} (orderId {}) broke a constraint other than the primary key", event.eventId(),
                    event.orderId(), violation);
            throw new CouponUsageNotRecordedException("The usage row broke a constraint other than "
                    + PRIMARY_KEY);
        }
    }

    /** Only the primary key: any other violation is a bug. */
    private static boolean isThePrimaryKey(DataIntegrityViolationException violation) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
                    && constraint.getConstraintName() != null
                    && constraint.getConstraintName().toLowerCase(Locale.ROOT).contains(PRIMARY_KEY)) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getMessage() != null
                    && sql.getMessage().toLowerCase(Locale.ROOT).contains(PRIMARY_KEY)) {
                return true;
            }
        }
        return false;
    }
}

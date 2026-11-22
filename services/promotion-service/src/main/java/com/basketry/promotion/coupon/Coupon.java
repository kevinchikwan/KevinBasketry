package com.basketry.promotion.coupon;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A coupon (DESIGN.md Section 2, promotion-service). The constructor refuses a
 * coupon that breaks a rule, so no code path can build one: the code is a
 * {@link CouponCode}, the discount a {@link Discount}, and a merchant id is
 * given exactly for a merchant coupon. Never deleted; only {@link #deactivate()}
 * changes it after creation. Whether it has expired is the pricing's question
 * (ROADMAP A5), against its clock.
 */
@Entity
@Table(name = "coupon")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "coupon_id")
    @SequenceGenerator(name = "coupon_id", sequenceName = "coupon_id_seq", allocationSize = 1)
    private Long id;

    @Convert(converter = CouponCodeConverter.class)
    @Column(updatable = false)
    private CouponCode code;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private CouponScope scope;

    @Column(updatable = false)
    private Long merchantId;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private DiscountType discountType;

    @Column(updatable = false)
    private BigDecimal discountValue;

    @Column(updatable = false)
    private Instant expiresAt;

    private boolean active;

    protected Coupon() {
    }

    /**
     * @param merchantId the owner for a {@link CouponScope#MERCHANT} coupon (above 0); null for a
     *                   {@link CouponScope#PLATFORM} one
     */
    public Coupon(CouponCode code, CouponScope scope, Long merchantId, Discount discount, Instant expiresAt) {
        if (code == null || scope == null || discount == null || expiresAt == null) {
            throw new InvalidCouponException("A coupon needs a code, a scope, a discount and an expiry.");
        }
        boolean merchantIdFits = scope == CouponScope.MERCHANT ? merchantId != null && merchantId > 0
                : merchantId == null;
        if (!merchantIdFits) {
            throw new InvalidCouponException(
                    "A merchant coupon has its merchant's id (above 0); a platform coupon has none.");
        }
        this.code = code;
        this.scope = scope;
        this.merchantId = merchantId;
        this.discountType = discount.type();
        this.discountValue = discount.value();
        this.expiresAt = expiresAt;
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public CouponCode getCode() {
        return code;
    }

    public CouponScope getScope() {
        return scope;
    }

    /** The owner of a merchant coupon; null for a platform coupon. */
    public Long getMerchantId() {
        return merchantId;
    }

    public Discount getDiscount() {
        return new Discount(discountType, discountValue);
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isActive() {
        return active;
    }

    /** Final: a deactivated coupon is refused at pricing from then on (COUPON_INACTIVE). */
    public void deactivate() {
        this.active = false;
    }
}

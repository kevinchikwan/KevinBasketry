package com.basketry.promotion.coupon;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /** Takes only a normalised code, so a lookup cannot miss on case or spaces. */
    Optional<Coupon> findByCode(CouponCode code);
}

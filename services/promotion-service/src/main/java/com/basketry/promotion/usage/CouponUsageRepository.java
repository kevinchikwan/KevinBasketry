package com.basketry.promotion.usage;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponUsageRepository extends JpaRepository<CouponUsage, UUID> {

    /** One coupon's usage count (GET /coupons/{id}, the deactivation). */
    long countByCouponId(long couponId);

    /** A list page's counts in one grouped query: each row a coupon id and its count; a coupon never used has no row. */
    @Query("select u.couponId, count(u) from CouponUsage u where u.couponId in :couponIds group by u.couponId")
    List<Object[]> countByCouponIds(@Param("couponIds") Collection<Long> couponIds);
}

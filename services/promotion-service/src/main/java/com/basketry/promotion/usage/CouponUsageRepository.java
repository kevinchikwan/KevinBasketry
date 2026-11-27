package com.basketry.promotion.usage;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponUsageRepository extends JpaRepository<CouponUsage, UUID> {

    long countByCouponId(long couponId);
}

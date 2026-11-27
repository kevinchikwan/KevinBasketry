package com.basketry.promotion.web;

import com.basketry.promotion.coupon.Coupon;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;

/**
 * A page of GET /coupons, the shape of identity-service's GET /accounts:
 * each item as GET /coupons/{id} answers it, with expired read at one instant
 * and the usage counts from one grouped query (absent means 0).
 */
public record CouponPage(List<CouponResponse> content, int page, int size, long totalElements, int totalPages) {

    static CouponPage from(Page<Coupon> page, Instant now, Map<Long, Long> usageCounts) {
        return new CouponPage(page.getContent().stream()
                .map(coupon -> CouponResponse.from(coupon, now, usageCounts.getOrDefault(coupon.getId(), 0L))).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}

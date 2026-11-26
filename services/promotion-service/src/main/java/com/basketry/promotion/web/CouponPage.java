package com.basketry.promotion.web;

import com.basketry.promotion.coupon.Coupon;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * A page of GET /coupons, the shape of identity-service's GET /accounts:
 * each item as GET /coupons/{id} answers it, with expired read at one instant.
 */
public record CouponPage(List<CouponResponse> content, int page, int size, long totalElements, int totalPages) {

    static CouponPage from(Page<Coupon> page, Instant now) {
        return new CouponPage(page.getContent().stream().map(coupon -> CouponResponse.from(coupon, now)).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}

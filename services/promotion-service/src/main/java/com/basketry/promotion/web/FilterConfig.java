package com.basketry.promotion.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The 64 KB body limit, on /internal/coupons/price and POST /coupons (the only
 * endpoints that read a body), ahead of Spring Security's filters.
 */
@Configuration(proxyBeanMethods = false)
class FilterConfig {

    /** Spring Boot registers Spring Security's filter chain at order -100; this runs just before it. */
    static final int BEFORE_SECURITY = -101;

    @Bean
    FilterRegistrationBean<PricingBodyLimitFilter> pricingBodyLimitFilter() {
        FilterRegistrationBean<PricingBodyLimitFilter> registration =
                new FilterRegistrationBean<>(new PricingBodyLimitFilter());
        registration.addUrlPatterns(InternalPricingController.PATH, CouponController.COLLECTION);
        registration.setOrder(BEFORE_SECURITY);
        return registration;
    }
}

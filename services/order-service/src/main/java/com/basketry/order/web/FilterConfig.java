package com.basketry.order.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The checkout's body limit, on POST /orders/checkout only, ahead of Spring Security's filters. */
@Configuration(proxyBeanMethods = false)
class FilterConfig {

    /** Spring Boot registers Spring Security's filter chain at order -100; this runs just before it. */
    static final int BEFORE_SECURITY = -101;

    @Bean
    FilterRegistrationBean<CheckoutBodyLimitFilter> checkoutBodyLimitFilter() {
        FilterRegistrationBean<CheckoutBodyLimitFilter> registration =
                new FilterRegistrationBean<>(new CheckoutBodyLimitFilter());
        registration.addUrlPatterns("/orders/checkout");
        registration.setOrder(BEFORE_SECURITY);
        return registration;
    }
}

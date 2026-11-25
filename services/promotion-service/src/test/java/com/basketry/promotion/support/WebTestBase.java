package com.basketry.promotion.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.basketry.promotion.config.InternalApiKey;
import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.coupon.CouponCode;
import com.basketry.promotion.coupon.CouponRepository;
import com.basketry.promotion.coupon.CouponScope;
import com.basketry.promotion.coupon.Discount;
import com.basketry.promotion.coupon.DiscountType;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The pricing endpoint through MockMvc, with the test key and coupons saved through JPA. */
@AutoConfigureMockMvc
public abstract class WebTestBase extends PromotionServiceTestBase {

    /** The key in src/test/resources/config/application.properties. Not a secret. */
    public static final String API_KEY = "test-only-internal-key-not-a-secret-000";
    public static final String PATH = "/internal/coupons/price";
    public static final Instant FAR_FUTURE = Instant.parse("2099-01-01T00:00:00Z");

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected CouponRepository coupons;

    protected Coupon savePlatform(String code, DiscountType type, String value) {
        return coupons.save(new Coupon(new CouponCode(code), CouponScope.PLATFORM, null,
                new Discount(type, new BigDecimal(value)), FAR_FUTURE));
    }

    protected Coupon saveMerchant(String code, long owner, DiscountType type, String value) {
        return coupons.save(new Coupon(new CouponCode(code), CouponScope.MERCHANT, owner,
                new Discount(type, new BigDecimal(value)), FAR_FUTURE));
    }

    /** POST with the key and a JSON body, as order-service sends it. */
    protected ResultActions price(String body) throws Exception {
        return mvc.perform(post(PATH).header(InternalApiKey.HEADER, API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** A body with the code and one merchant line per pair of id and subtotal, both written as given. */
    protected static String body(String codeJson, String... idsAndSubtotals) {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < idsAndSubtotals.length; i += 2) {
            lines.append(i == 0 ? "" : ",").append("{\"merchantId\":").append(idsAndSubtotals[i])
                    .append(",\"subtotal\":").append(idsAndSubtotals[i + 1]).append('}');
        }
        return "{\"code\":" + codeJson + ",\"merchants\":[" + lines + "]}";
    }
}

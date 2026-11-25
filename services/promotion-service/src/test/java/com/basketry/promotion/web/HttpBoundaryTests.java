package com.basketry.promotion.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.promotion.config.InternalApiKey;
import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.coupon.CouponCode;
import com.basketry.promotion.coupon.CouponRepository;
import com.basketry.promotion.coupon.CouponScope;
import com.basketry.promotion.coupon.Discount;
import com.basketry.promotion.coupon.DiscountType;
import com.basketry.promotion.support.PromotionServiceTestBase;
import com.basketry.promotion.support.WebTestBase;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The 64 KB body limit over a real port (DESIGN.md Section 3,
 * promotion-service), as order-service's HttpBoundaryTests: a chunked body has
 * no Content-Length, and MockMvc never chunks a body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpBoundaryTests extends PromotionServiceTestBase {

    private static final String BODY = "{\"code\":\"SAVE10\",\"merchants\":[{\"merchantId\":3,\"subtotal\":25.50}]}";

    @LocalServerPort
    private int port;

    @Autowired
    private CouponRepository coupons;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void aCoupon() {
        coupons.save(new Coupon(new CouponCode("SAVE10"), CouponScope.PLATFORM, null,
                new Discount(DiscountType.PERCENTAGE, new BigDecimal("10")), WebTestBase.FAR_FUTURE));
    }

    /** A body exactly {@code size} bytes long: the body, padded with spaces inside the object. */
    static String bodyOf(int size) {
        return BODY.substring(0, BODY.length() - 1) + " ".repeat(size - BODY.length()) + "}";
    }

    /** Sent with no length, so HttpClient uses chunked transfer encoding. */
    static HttpRequest.BodyPublisher chunked(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes));
    }

    private HttpResponse<String> send(HttpRequest.BodyPublisher body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + WebTestBase.PATH))
                .header(InternalApiKey.HEADER, WebTestBase.API_KEY)
                .header("Content-Type", "application/json")
                .POST(body).build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Chunked: one byte over 64 KB is a 413 with its code; exactly 64 KB is
     * read and priced. Mutation: the limit decided by Content-Length only (a
     * chunked body has none, so the big one is priced).
     */
    @Test
    void aChunkedBodyIsLimitedByTheBytesRead() throws Exception {
        HttpResponse<String> over = send(chunked(bodyOf(PricingBodyLimitFilter.LIMIT + 1)));
        HttpResponse<String> atTheLimit = send(chunked(bodyOf(PricingBodyLimitFilter.LIMIT)));

        assertThat(over.statusCode()).isEqualTo(413);
        assertThat(over.body()).contains("\"code\":\"BODY_TOO_LARGE\"").doesNotContain("SAVE10");
        assertThat(atTheLimit.statusCode()).isEqualTo(200);
    }

    /** A declared Content-Length over the limit is refused before reading. Mutation: the filter not registered. */
    @Test
    void aDeclaredLengthOverTheLimitIs413() throws Exception {
        HttpResponse<String> over = send(HttpRequest.BodyPublishers.ofString(bodyOf(PricingBodyLimitFilter.LIMIT + 1)));

        assertThat(over.statusCode()).isEqualTo(413);
        assertThat(over.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    }
}

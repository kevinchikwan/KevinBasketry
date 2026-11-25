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
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.filter.AbstractRequestLoggingFilter;

/**
 * The coupon code, a subtotal and the internal key over HTTP: no log line and
 * no response ever holds them, whatever the request, while Spring's web layer
 * logs at TRACE, Tomcat at DEBUG and our own loggers at DEBUG. As
 * order-service's HttpCardNumberLeakTests. Spring logs a resolved exception's
 * message at DEBUG, and Jackson's message quotes the input ("Cannot coerce
 * String value (...)", "Duplicate Object property ..."), which is why the
 * controller reads the body itself and keeps only the path.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "logging.level.org.springframework.web=TRACE",
        "logging.level.org.apache.tomcat=DEBUG",
        "logging.level.org.apache.coyote=DEBUG",
        "logging.level.org.apache.catalina=DEBUG",
        "logging.level.com.basketry=DEBUG"})
class HttpLeakTests extends PromotionServiceTestBase {

    private static final String CODE = "LEAKCODE7Q";
    private static final String MISSING_CODE = "LEAKMISS7Q";
    private static final String SUBTOTAL = "4242424.17";

    @LocalServerPort
    private int port;

    @Autowired
    private CouponRepository coupons;

    @Autowired
    private ApplicationContext context;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> post(HttpRequest.BodyPublisher body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + WebTestBase.PATH))
                .header(InternalApiKey.HEADER, WebTestBase.API_KEY)
                .header("Content-Type", "application/json")
                .POST(body).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.BodyPublisher text(String body) {
        return HttpRequest.BodyPublishers.ofString(body);
    }

    private static String body(String code, String subtotal) {
        return "{\"code\":\"" + code + "\",\"merchants\":[{\"merchantId\":3,\"subtotal\":" + subtotal + "}]}";
    }

    /**
     * Mutations: the coupon code back in CouponPricingService's DEBUG line;
     * the body read by Spring ({@code @RequestBody PriceRequest}, Jackson's
     * message in Spring's DEBUG lines); {@code spring.mvc.log-request-details: true}
     * (the key in a TRACE line).
     */
    @Test
    void theCodeASubtotalAndTheKeyAreInNoLogLineAndNoResponse(CapturedOutput output) throws Exception {
        coupons.save(new Coupon(new CouponCode(CODE), CouponScope.PLATFORM, null,
                new Discount(DiscountType.PERCENTAGE, new BigDecimal("10")), WebTestBase.FAR_FUTURE));
        List<HttpResponse<String>> responses = new ArrayList<>();

        // Priced, and refused (no coupon has the code).
        responses.add(post(text(body(CODE, SUBTOTAL))));
        responses.add(post(text(body(MISSING_CODE, SUBTOTAL))));
        // A body rule, a code the rule refuses, a wrong type, broken JSON, the code as a field name.
        responses.add(post(text(body(CODE, SUBTOTAL + "1"))));
        responses.add(post(text(body("\\u017f" + CODE, SUBTOTAL))));
        responses.add(post(text(body(CODE, "\"" + SUBTOTAL + "\""))));
        responses.add(post(text("{\"code\":\"" + CODE + "\",\"merchants\":[{\"merchantId\":3,\"subtotal\":" + SUBTOTAL + "x")));
        responses.add(post(text("{\"" + CODE + "\":1,\"" + CODE + "\":1,\"code\":\"" + CODE + "\"}")));
        responses.add(post(text("{\"" + CODE + "\":1," + body(CODE, SUBTOTAL).substring(1))));
        // A body over the limit, chunked.
        responses.add(post(HttpBoundaryTests.chunked(body(CODE, SUBTOTAL) + " ".repeat(PricingBodyLimitFilter.LIMIT))));

        assertThat(responses).extracting(HttpResponse::statusCode)
                .containsExactly(200, 409, 400, 400, 400, 400, 400, 400, 413);
        // The check would be empty if the logging were off.
        assertThat(output).contains("Read \"application/json").contains("Completed 400 BAD_REQUEST")
                .contains("Coupon refused: COUPON_NOT_FOUND");
        assertThat(output.getAll()).doesNotContain(CODE).doesNotContain(MISSING_CODE).doesNotContain(SUBTOTAL)
                .doesNotContain(WebTestBase.API_KEY);
        assertThat(responses).allSatisfy(response -> assertThat(response.body())
                .doesNotContain(CODE).doesNotContain(MISSING_CODE).doesNotContain(SUBTOTAL));
        assertThat(context.getBeansOfType(AbstractRequestLoggingFilter.class)).isEmpty();
    }
}

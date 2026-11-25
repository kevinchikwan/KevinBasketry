package com.basketry.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.order.support.WebTestBase;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * The card number over HTTP (DESIGN.md Section 2, order-service, the card
 * number): no log line and no response ever holds it, whatever the request,
 * while Spring's web layer logs at TRACE and Tomcat at DEBUG. The controller
 * reads the body itself, so neither the request object nor Jackson's message
 * (which quotes a string sent for a number field) reaches Spring's log
 * (HttpJacksonMessageLeakTests covers each way Jackson can fail). No
 * request-logging filter exists, and request details (headers, so the bearer
 * token) are never logged.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "logging.level.org.springframework.web=TRACE",
        "logging.level.org.apache.tomcat=DEBUG",
        "logging.level.org.apache.coyote=DEBUG",
        "logging.level.org.apache.catalina=DEBUG",
        "logging.level.com.basketry=DEBUG"})
class HttpCardNumberLeakTests extends WebTestBase {

    private static final String SENTINEL = "4929117300568841";
    private static final String SENTINEL_HEAD = SENTINEL.substring(0, 12);

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> post(String key, HttpRequest.BodyPublisher body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders/checkout"))
                .header("Authorization", "Bearer " + BUYER_JWT)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(body).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.BodyPublisher text(String body) {
        return HttpRequest.BodyPublishers.ofString(body);
    }

    /**
     * Mutations: the body read by Spring again ({@code @RequestBody
     * CheckoutRequest}: the number sent as a string for expectedTotal is quoted
     * in Spring's DEBUG lines); spring.mvc.log-request-details true (the bearer
     * token in a TRACE line); Jackson's message as the 400 detail; the
     * digit-run check on unknown field names removed ("a" + the number
     * echoed). Spring no longer holds the request object, so its masked
     * toString is checked in CheckoutRequestTests.
     */
    @Test
    void theCardNumberIsInNoLogLineAndNoResponse(CapturedOutput output) throws Exception {
        givenTheStandardCart();
        List<HttpResponse<String>> responses = new ArrayList<>();

        // A checkout and its replay.
        responses.add(post("key-0001", text(standardBody(SENTINEL))));
        responses.add(post("key-0001", text(standardBody(SENTINEL))));
        // A body rule, a wrong type, broken JSON, the number as a field name.
        responses.add(post("key-0002", text(standardBody(SENTINEL + "000000"))));
        responses.add(post("key-0003", text(standardBody(SENTINEL).replace("\"" + SENTINEL + "\"", SENTINEL))));
        responses.add(post("key-0004", text("{\"cardNumber\":\"" + SENTINEL + "\",\"lines\":[{\"productId\":1x")));
        responses.add(post("key-0005", text("{\"" + SENTINEL + "\":1,\"cardNumber\":\"" + SENTINEL + "\"}")));
        responses.add(post("key-0008", text("{\"a" + SENTINEL + "\":1,\"lines\":[{\"productId\":10,\"quantity\":1,"
                + "\"a" + SENTINEL + "\":1}],\"cardNumber\":\"" + CARD + "\",\"expectedTotal\":2.50}")));
        // The number as a string in another field: Jackson's message quotes it ("Cannot coerce String value").
        responses.add(post("key-0009", text(standardBody(CARD).replace("5.00", "\"" + SENTINEL + "\""))));
        // A refusal after the body, and a body over the limit, chunked.
        responses.add(post("key-0006", text(standardBody(SENTINEL).replace("5.00", "4.00"))));
        byte[] big = (standardBody(SENTINEL) + " ".repeat(CheckoutBodyLimitFilter.LIMIT)).getBytes(StandardCharsets.UTF_8);
        responses.add(post("key-0007", HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(big))));

        assertThat(responses).extracting(HttpResponse::statusCode)
                .containsExactly(202, 200, 400, 400, 400, 400, 400, 400, 409, 413);
        // The check would be empty if the logging were off.
        assertThat(output).contains("Read \"application/json").contains("Completed 400 BAD_REQUEST")
                .contains("Completed 202 ACCEPTED");
        assertThat(output.getAll()).doesNotContain(SENTINEL_HEAD).doesNotContain(BUYER_JWT);
        assertThat(responses).allSatisfy(response -> assertThat(response.body()).doesNotContain(SENTINEL_HEAD));
        assertThat(context.getBeansOfType(AbstractRequestLoggingFilter.class)).isEmpty();
    }
}

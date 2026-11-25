package com.basketry.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.order.support.WebTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * The card number in the bodies that make Jackson fail (ROADMAP F, found by
 * promotion-service's HttpLeakTests): when Spring reads a @RequestBody, a
 * Jackson error reaches Spring's DEBUG and TRACE lines with Jackson's message,
 * which can quote the input. Each case: no log line and no response holds the
 * number, while Spring's web layer logs at TRACE, Tomcat at DEBUG and our own
 * loggers at DEBUG.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "logging.level.org.springframework.web=TRACE",
        "logging.level.org.apache.tomcat=DEBUG",
        "logging.level.org.apache.coyote=DEBUG",
        "logging.level.org.apache.catalina=DEBUG",
        "logging.level.com.basketry=DEBUG",
        // Its own context: sharing HttpCardNumberLeakTests' cached one leaves the log bound to the
        // output capture of whichever class started it, and the other class captures nothing.
        "basketry.test.context=jackson-message-leak"})
class HttpJacksonMessageLeakTests extends WebTestBase {

    private static final String SENTINEL = "4929117300568841";
    private static final String SENTINEL_HEAD = SENTINEL.substring(0, 12);
    private static final String LINES = "\"lines\":[{\"productId\":10,\"quantity\":1},{\"productId\":20,\"quantity\":2}]";
    private static final AtomicInteger KEYS = new AtomicInteger();

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    static Stream<Arguments> bodies() {
        String card = "\"cardNumber\":\"" + CARD + "\"";
        return Stream.of(
                Arguments.of("cardNumber as a JSON number",
                        "{" + LINES + ",\"cardNumber\":" + SENTINEL + ",\"expectedTotal\":5.00}"),
                Arguments.of("expectedTotal as a string",
                        "{" + LINES + "," + card + ",\"expectedTotal\":\"" + SENTINEL + "\"}"),
                Arguments.of("productId as a string",
                        "{\"lines\":[{\"productId\":\"" + SENTINEL + "\",\"quantity\":1}]," + card + ",\"expectedTotal\":5.00}"),
                Arguments.of("quantity as a string",
                        "{\"lines\":[{\"productId\":10,\"quantity\":\"" + SENTINEL + "\"}]," + card + ",\"expectedTotal\":5.00}"),
                Arguments.of("the number as a repeated field name",
                        "{\"" + SENTINEL + "\":1,\"" + SENTINEL + "\":2," + LINES + "," + card + ",\"expectedTotal\":5.00}"),
                Arguments.of("the number in a repeated field name inside an unknown field",
                        "{\"x\":{\"" + SENTINEL + "\":1,\"" + SENTINEL + "\":2}," + LINES + "," + card + ",\"expectedTotal\":5.00}"),
                Arguments.of("the number as an unknown field name",
                        "{\"" + SENTINEL + "\":1," + LINES + "," + card + ",\"expectedTotal\":5.00}"),
                Arguments.of("the number after a letter in an unknown field name",
                        "{\"a" + SENTINEL + "\":1," + LINES + "," + card + ",\"expectedTotal\":5.00}"),
                Arguments.of("the number as a trailing number",
                        "{" + LINES + "," + card + ",\"expectedTotal\":5.00} " + SENTINEL),
                Arguments.of("the number as a trailing string",
                        "{" + LINES + "," + card + ",\"expectedTotal\":5.00} \"" + SENTINEL + "\""),
                Arguments.of("a real body followed by trailing tokens",
                        "{" + LINES + ",\"cardNumber\":\"" + SENTINEL + "\",\"expectedTotal\":5.00} x"));
    }

    /**
     * Mutation: the body read by Spring again ({@code @RequestBody CheckoutRequest}):
     * expectedTotal, productId and quantity sent as strings leak in three
     * DEBUG and TRACE lines ("Cannot coerce String value (...)").
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("bodies")
    void theCardNumberIsInNoLogLineAndNoResponse(String name, String body, CapturedOutput output) throws Exception {
        givenTheStandardCart();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders/checkout"))
                        .header("Authorization", "Bearer " + BUYER_JWT)
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", "leak-key-" + KEYS.incrementAndGet())
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        // The check would be empty if the logging were off.
        assertThat(output).contains("Completed 400 BAD_REQUEST");
        assertThat(response.body()).doesNotContain(SENTINEL_HEAD);
        assertThat(output.getAll()).doesNotContain(SENTINEL_HEAD);
    }
}

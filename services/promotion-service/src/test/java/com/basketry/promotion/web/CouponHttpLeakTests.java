package com.basketry.promotion.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.promotion.support.CouponApiTestBase;
import com.basketry.promotion.support.PromotionServiceTestBase;
import com.basketry.promotion.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
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
 * The coupon endpoints over a real port (ROADMAP A7), with Spring's web layer
 * and Spring Security at TRACE and our own loggers at DEBUG. A coupon code is
 * not a secret (it is the owner's, in every answer), so it is not checked.
 * What is checked: a sentinel sent in a refused body, as a wrong-typed or
 * unquoted value, a rule-breaking value, an unknown field's name or value, a
 * repeated name or a trailing token, never reaches a log line or an answer
 * (Jackson's message quotes the input); and no bearer token, Admin's or a
 * merchant's, ever reaches a log line, successful answers included. Its own
 * context (the last property), so its output capture is not shared.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "logging.level.org.springframework.web=TRACE",
        "logging.level.org.springframework.security=TRACE",
        "logging.level.org.apache.tomcat=DEBUG",
        "logging.level.org.apache.coyote=DEBUG",
        "logging.level.org.apache.catalina=DEBUG",
        "logging.level.com.basketry=DEBUG",
        "basketry.test.context=coupon-http-leak"})
class CouponHttpLeakTests extends PromotionServiceTestBase {

    /** Letters and digits only, so no JSON or URL escaping changes it on the way. */
    private static final String SENTINEL = "Zq7Pw9LeakSent1nel";
    private static final String ADMIN = TestTokens.admin();
    private static final String MERCHANT = TestTokens.merchant(31, CouponApiTestBase.MERCHANT);

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String expiry() {
        return Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static String body(String code, String scope, String type, String value, String expiresAt) {
        return "{\"code\":" + code + ",\"scope\":" + scope + ",\"discountType\":" + type + ",\"discountValue\":"
                + value + ",\"expiresAt\":" + expiresAt + "}";
    }

    private static String q(String text) {
        return "\"" + text + "\"";
    }

    static Stream<Arguments> refused() {
        String ok = body(q("LEAKOK1"), q("PLATFORM"), q("FIXED"), "5.00", q(expiry()));
        return Stream.of(
                Arguments.of("the code with a character the rule refuses", body(q(SENTINEL + "!"),
                        q("PLATFORM"), q("FIXED"), "5.00", q(expiry()))),
                Arguments.of("the scope", body(q("LEAKOK1"), q(SENTINEL), q("FIXED"), "5.00", q(expiry()))),
                Arguments.of("the discount type", body(q("LEAKOK1"), q("PLATFORM"), q(SENTINEL), "5.00", q(expiry()))),
                Arguments.of("the expiry", body(q("LEAKOK1"), q("PLATFORM"), q("FIXED"), "5.00", q(SENTINEL))),
                Arguments.of("the value as a string", body(q("LEAKOK1"), q("PLATFORM"), q("FIXED"), q(SENTINEL),
                        q(expiry()))),
                Arguments.of("the value unquoted", body(q("LEAKOK1"), q("PLATFORM"), q("FIXED"), SENTINEL,
                        q(expiry()))),
                Arguments.of("the merchant id as a string", ok.replace("{", "{\"merchantId\":" + q(SENTINEL) + ",")),
                Arguments.of("the merchant id unquoted", ok.replace("{", "{\"merchantId\":" + SENTINEL + ",")),
                Arguments.of("the code as an object", ok.replace("\"LEAKOK1\"", "{\"x\":" + q(SENTINEL) + "}")),
                Arguments.of("an unknown field's value", ok.replace("{", "{\"note\":" + q(SENTINEL) + ",")),
                Arguments.of("an unknown field's name with a digit run",
                        ok.replace("{", "{" + q(SENTINEL + "4929117300568841") + ":1,")),
                Arguments.of("an unknown field's name with punctuation", ok.replace("{", "{" + q(SENTINEL + "!") + ":1,")),
                Arguments.of("a repeated unknown name", ok.replace("{", "{" + q(SENTINEL + "!") + ":1,"
                        + q(SENTINEL + "!") + ":2,")),
                Arguments.of("a repeated field", ok.replace("{", "{\"scope\":" + q(SENTINEL) + ",")),
                Arguments.of("a trailing token", ok + " " + SENTINEL),
                Arguments.of("a trailing string", ok + " " + q(SENTINEL)),
                Arguments.of("broken JSON after it", "{\"code\":\"LEAKOK1\",\"scope\":" + SENTINEL),
                Arguments.of("an array of it", "[" + q(SENTINEL) + "]"),
                Arguments.of("a string body", q(SENTINEL)));
    }

    /**
     * Mutations: the body read by Spring ({@code @RequestBody} with the
     * request type: Jackson's message in Spring's DEBUG lines); the
     * unreadable body's detail with Jackson's message; an unknown name shown
     * whatever it is; a rule's message quoting the value.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("refused")
    void aSentinelInARefusedBodyIsInNoLogLineAndNoAnswer(String name, String body, CapturedOutput output)
            throws Exception {
        List<HttpResponse<String>> answers = List.of(send("POST", "/coupons", ADMIN, body),
                send("POST", "/coupons", MERCHANT, body));

        assertThat(answers).extracting(HttpResponse::statusCode).containsOnly(400);
        // The check would be empty if the logging were off.
        assertThat(output).contains("Completed 400");
        List<String> leaks = new ArrayList<>();
        output.getAll().lines().filter(line -> line.contains(SENTINEL)).forEach(line -> leaks.add("log :: " + line));
        answers.stream().filter(answer -> answer.body().contains(SENTINEL))
                .forEach(answer -> leaks.add("answer :: " + answer.body()));
        assertThat(leaks).isEmpty();
    }

    /**
     * Successful answers and refusals after the body (403, 409, 404): the
     * tokens are in no log line. Spring writes the answer object at TRACE,
     * code included, which is allowed. Mutation: spring.mvc.log-request-details:
     * true (the Authorization header in a TRACE line).
     */
    @Test
    void noTokenIsInALogLine(CapturedOutput output) throws Exception {
        List<HttpResponse<String>> answers = new ArrayList<>();
        HttpResponse<String> created = send("POST", "/coupons", MERCHANT,
                body(q("LEAKOWN1"), q("MERCHANT"), q("PERCENTAGE"), "10", q(expiry())));
        answers.add(created);
        String id = JsonPath.read(created.body(), "$.id").toString();
        answers.add(send("GET", "/coupons/" + id, MERCHANT, null));
        answers.add(send("GET", "/coupons/" + id, ADMIN, null));
        answers.add(send("POST", "/coupons", ADMIN, body(q("LEAKPLAT1"), q("PLATFORM"), q("FIXED"), "5", q(expiry()))));
        answers.add(send("POST", "/coupons", MERCHANT, body(q("LEAKOWN1"), q("MERCHANT"), q("FIXED"), "5",
                q(expiry()))));
        answers.add(send("POST", "/coupons", MERCHANT, body(q("LEAKPLAT2"), q("PLATFORM"), q("FIXED"), "5",
                q(expiry()))));
        answers.add(send("GET", "/coupons/999999", ADMIN, null));

        assertThat(answers).extracting(HttpResponse::statusCode).containsExactly(201, 200, 200, 201, 409, 403, 404);
        assertThat(output).contains("Writing [CouponResponse[");
        List<String> leaks = new ArrayList<>();
        for (String token : List.of(ADMIN, MERCHANT)) {
            output.getAll().lines().filter(line -> line.contains(token))
                    .forEach(line -> leaks.add("log :: " + line.replace(token, "<token>")));
            // Each half of a token too: a line that cut it would still hold one.
            String signature = token.substring(token.lastIndexOf('.') + 1);
            output.getAll().lines().filter(line -> line.contains(signature))
                    .forEach(line -> leaks.add("log (signature) :: " + line.replace(signature, "<signature>")));
        }
        assertThat(leaks).isEmpty();
    }
}

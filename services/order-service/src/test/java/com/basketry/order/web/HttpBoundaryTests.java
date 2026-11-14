package com.basketry.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.order.support.WebTestBase;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * What only a real server shows (DESIGN.md Section 3, order-service): the CORS
 * preflight answered before authentication, the bare 401, the exposed
 * Location, and the 64 KB body limit on chunked bodies, which have no
 * Content-Length. MockMvc never chunks a body and passes the filters
 * differently, so these run over a real port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpBoundaryTests extends WebTestBase {

    private static final String ORIGIN = "http://localhost:5173";

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private URI checkoutUri() {
        return URI.create("http://localhost:" + port + "/orders/checkout");
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** A body exactly {@code size} bytes long: the standard body, padded with spaces inside the object. */
    private static String standardBodyOf(int size) {
        String body = standardBody(CARD);
        return body.substring(0, body.length() - 1) + " ".repeat(size - body.length()) + "}";
    }

    /** Sent with no length, so HttpClient uses chunked transfer encoding. */
    private static HttpRequest.BodyPublisher chunked(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes));
    }

    private HttpRequest.Builder checkout(String key) {
        return HttpRequest.newBuilder(checkoutUri())
                .header("Authorization", "Bearer " + BUYER_JWT)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key);
    }

    /**
     * A browser's preflight, which carries no token, is answered before
     * authentication, and allows Idempotency-Key. Mutations: CORS left out of
     * the chain (the preflight is a 401); Idempotency-Key not an allowed header
     * (403: no browser could check out).
     */
    @Test
    void thePreflightIsAnsweredBeforeAuthenticationAndAllowsIdempotencyKey() throws Exception {
        HttpResponse<String> preflight = send(HttpRequest.newBuilder(checkoutUri())
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key")
                .build());

        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ORIGIN);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Headers").orElseThrow().toLowerCase())
                .contains("idempotency-key");
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
    }

    /** Mutation: allowed origins "*". */
    @Test
    void anotherOriginIsRefused() throws Exception {
        HttpResponse<String> preflight = send(HttpRequest.newBuilder(checkoutUri())
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .build());

        assertThat(preflight.statusCode()).isEqualTo(403);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    /** A browser may read Location from the 202. Mutation: Location not exposed. */
    @Test
    void theCheckoutsLocationIsExposedToTheBrowser() throws Exception {
        givenTheStandardCart();

        HttpResponse<String> response = send(checkout("key-0001").header("Origin", ORIGIN)
                .POST(HttpRequest.BodyPublishers.ofString(standardBody(CARD))).build());

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).startsWith("/orders/ORD-"));
        assertThat(response.headers().firstValue("Access-Control-Expose-Headers")).hasValue("Location");
    }

    /**
     * No token: 401 with an empty body and a Bearer challenge, as the other
     * services. Mutation: the entry point replaced by a plain 401 (no
     * WWW-Authenticate).
     */
    @Test
    void noTokenIsABare401WithABearerChallenge() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders"))
                .GET().build());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                challenge -> assertThat(challenge).startsWith("Bearer"));
    }

    /**
     * Chunked: one byte over 64 KB is a 413 with its code; exactly 64 KB is
     * read and checked out. Mutation: the limit decided by Content-Length only
     * (a chunked body has none, so the big one is checked out).
     */
    @Test
    void aChunkedBodyIsLimitedByTheBytesRead() throws Exception {
        givenTheStandardCart();

        HttpResponse<String> over = send(checkout("key-0001")
                .POST(chunked(standardBodyOf(CheckoutBodyLimitFilter.LIMIT + 1))).build());
        HttpResponse<String> atTheLimit = send(checkout("key-0002")
                .POST(chunked(standardBodyOf(CheckoutBodyLimitFilter.LIMIT))).build());

        assertThat(over.statusCode()).isEqualTo(413);
        assertThat(over.body()).contains("\"code\":\"BODY_TOO_LARGE\"").doesNotContain(CARD);
        assertThat(atTheLimit.statusCode()).isEqualTo(202);
        assertThat(rowCounts().getFirst()).isEqualTo(1);
    }

    /** A declared Content-Length over the limit is refused before reading. Mutation: the limit check removed. */
    @Test
    void aDeclaredLengthOverTheLimitIs413() throws Exception {
        givenTheStandardCart();

        HttpResponse<String> over = send(checkout("key-0001")
                .POST(HttpRequest.BodyPublishers.ofString(standardBodyOf(CheckoutBodyLimitFilter.LIMIT + 1))).build());

        assertThat(over.statusCode()).isEqualTo(413);
        assertThat(rowCounts().getFirst()).isZero();
    }
}

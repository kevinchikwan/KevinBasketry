package com.basketry.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.basketry.merchant.support.MerchantServiceTestBase;
import com.basketry.merchant.support.MockIdentityConfig;
import com.basketry.merchant.support.TestTokens;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Today's exact 400, 401, 405 and 415 answers of merchant-service's three
 * endpoints with a body (POST /merchants, POST /merchants/{code}/account-link,
 * PATCH /merchants/{code}), byte for byte, and the status of every request
 * shape that succeeds. Written, and proven on the unchanged code, before the
 * body is kept out of Spring's log (ROADMAP F3), which must leave every one
 * of them unchanged, except the unknown field names in the two
 * *UnsafeUnknownFieldNames* tests. MockMvc adds the request's character
 * encoding (UTF-8) to a type without a charset, as Spring then reports it.
 */
class MerchantBadRequestBodiesTests extends MerchantServiceTestBase {

    private static final String PROBLEM = "application/problem+json";
    private static final String UNREADABLE = "Failed to read request";
    private static final String INVALID = "Invalid request content.";
    private static final String TEXT_PLAIN = "Content-Type 'text/plain;charset=UTF-8' is not supported.";
    private static final String NO_TYPE = "Content-Type 'application/octet-stream' is not supported.";
    private static final String XML = "Content-Type 'application/xml;charset=UTF-8' is not supported.";
    private static final String CANNOT_PARSE = "Could not parse Content-Type.";
    private static final String NOTHING_TO_UPDATE = "Give at least one of commissionRate, kycVerified and active.";
    private static final String BAD = "Bad Request";
    private static final String UNSUPPORTED = "Unsupported Media Type";
    private static final AtomicInteger UNIQUE = new AtomicInteger();

    enum Endpoint { CREATE, RETRY, UPDATE }

    @BeforeEach
    void identityAnswersEveryLink() {
        // Whatever reaches identity-service is linked; most requests here never get that far.
        identity.expect(ExpectedCount.between(0, 100), requestTo(MockIdentityConfig.MERCHANT_LINK_URL))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":7,\"buyerActive\":true,\"merchantActive\":true,\"outcome\":\"CREATED\"}"));
    }

    /** A fresh path each time: a retry links its PENDING merchant, so it can only succeed once. */
    private String pathOf(Endpoint endpoint) {
        int n = UNIQUE.incrementAndGet();
        return switch (endpoint) {
            case CREATE -> "/merchants";
            case RETRY -> "/merchants/" + savePending("pending" + n + "@example.com").getMerchantCode() + "/account-link";
            case UPDATE -> "/merchants/" + saveLinked("linked" + n + "@example.com").getMerchantCode();
        };
    }

    private static HttpMethod methodOf(Endpoint endpoint) {
        return endpoint == Endpoint.UPDATE ? HttpMethod.PATCH : HttpMethod.POST;
    }

    private static int ok(Endpoint endpoint) {
        return endpoint == Endpoint.CREATE ? 201 : 200;
    }

    /** A body each endpoint accepts; {@code end} goes into a free-text value, to test charsets. */
    private static String valid(Endpoint endpoint, String end) {
        int n = UNIQUE.incrementAndGet();
        return switch (endpoint) {
            case CREATE -> "{\"name\":\"Shop " + n + end + "\",\"category\":\"Groceries\",\"kycVerified\":true,"
                    + "\"commissionRate\":5.00,\"loginEmail\":\"shop" + n + "@example.com\","
                    + "\"initialPassword\":\"Corr3ct-Hors" + end + "!\"}";
            case RETRY -> "{\"email\":\"retry" + n + "@example.com\",\"initialPassword\":\"Corr3ct-Hors" + end + "!\"}";
            case UPDATE -> "{\"active\":false}";
        };
    }

    private static String problem(String detail, String instance, int status, String title) {
        return "{\"detail\":\"" + detail + "\",\"instance\":\"" + instance + "\",\"status\":" + status
                + ",\"title\":\"" + title + "\"}";
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private MockHttpServletResponse send(HttpMethod method, String path, String contentType, byte[] body,
                                         boolean token) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path);
        if (token) {
            request.header("Authorization", "Bearer " + TestTokens.admin());
        }
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (body != null) {
            request.content(body);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    /** As Admin, on the endpoint's own method: the status, and for an answer with a detail the exact body. */
    private void assertShape(Endpoint endpoint, String contentType, byte[] body, int status, String detail,
                             String title) throws Exception {
        String path = pathOf(endpoint);
        MockHttpServletResponse response = send(methodOf(endpoint), path, contentType, body, true);

        assertThat(response.getStatus()).as("status for %s", endpoint).isEqualTo(status);
        if (detail != null) {
            assertThat(response.getContentAsString()).isEqualTo(problem(detail, path, status, title));
            assertThat(response.getContentType()).isEqualTo(PROBLEM);
        }
    }

    private void assertSuccess(Endpoint endpoint, String contentType, byte[] body) throws Exception {
        assertShape(endpoint, contentType, body, ok(endpoint), null, null);
    }

    /** Unreadable JSON, a trailing token, an array: the same 400 everywhere. Mutation: problem details disabled. */
    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void unreadableJsonIs400(Endpoint endpoint) throws Exception {
        for (String body : new String[] {"{x", "{\"a\":1} x", "[]"}) {
            assertShape(endpoint, "application/json", utf8(body), 400, UNREADABLE, BAD);
        }
    }

    /**
     * Empty and blank bodies. A missing body is a 400 on create and update,
     * but the retry's body is optional: no body, an empty one, the JSON literal
     * null and {} are all a retry without one (200), while whitespace is not
     * JSON (400). An unsupported type is a 415 even with no body. Mutation:
     * problem details disabled.
     */
    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void anEmptyOrBlankBodyIsAnsweredAsBefore(Endpoint endpoint) throws Exception {
        boolean optional = endpoint == Endpoint.RETRY;
        if (optional) {
            assertSuccess(endpoint, null, null);
            assertSuccess(endpoint, "application/json", null);
            assertSuccess(endpoint, "application/json", utf8("null"));
            assertSuccess(endpoint, "application/json", utf8("{}"));
        } else {
            assertShape(endpoint, null, null, 400, UNREADABLE, BAD);
            assertShape(endpoint, "application/json", null, 400, UNREADABLE, BAD);
            assertShape(endpoint, "application/json", utf8("null"), 400, UNREADABLE, BAD);
            assertShape(endpoint, "application/json", utf8("{}"), 400,
                    endpoint == Endpoint.CREATE ? INVALID : NOTHING_TO_UPDATE, BAD);
        }
        assertShape(endpoint, "text/plain", null, 415, TEXT_PLAIN, UNSUPPORTED);
        assertShape(endpoint, "application/json", utf8("   "), 400, UNREADABLE, BAD);
        assertShape(endpoint, "text/plain", utf8("   "), 415, TEXT_PLAIN, UNSUPPORTED);
    }

    /** Not JSON, or no Content-Type, with a body: 415 naming the type. Mutation: problem details disabled. */
    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void anotherTypeIs415(Endpoint endpoint) throws Exception {
        assertShape(endpoint, "text/plain", utf8("{}"), 415, TEXT_PLAIN, UNSUPPORTED);
        assertShape(endpoint, null, utf8("{}"), 415, NO_TYPE, UNSUPPORTED);
        assertShape(endpoint, null, utf8(valid(endpoint, "e")), 415, NO_TYPE, UNSUPPORTED);
        assertShape(endpoint, "application/xml", utf8(valid(endpoint, "e")), 415, XML, UNSUPPORTED);
        assertShape(endpoint, "text/plain", utf8(valid(endpoint, "e")), 415, TEXT_PLAIN, UNSUPPORTED);
    }

    /**
     * Any JSON type and charset is read: a non-Unicode charset decodes the body
     * (an ISO-8859-1 e-acute), a Unicode one is left to Jackson (UTF-8 bytes
     * labelled UTF-16 are read). Mutation: onboarding answered 200 instead of 201.
     */
    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void everyJsonTypeAndCharsetIsRead(Endpoint endpoint) throws Exception {
        assertSuccess(endpoint, "application/json;charset=UTF-8", utf8(valid(endpoint, "e")));
        assertSuccess(endpoint, "application/json;charset=ISO-8859-1",
                valid(endpoint, "e").getBytes(StandardCharsets.ISO_8859_1));
        assertSuccess(endpoint, "application/json;charset=UTF-16", valid(endpoint, "e").getBytes(StandardCharsets.UTF_16));
        assertSuccess(endpoint, "application/json;charset=UTF-16", utf8(valid(endpoint, "e")));
        assertSuccess(endpoint, "application/vnd.x+json", utf8(valid(endpoint, "e")));
        assertSuccess(endpoint, "application/json;charset=ISO-8859-1",
                valid(endpoint, "\u00e9").getBytes(StandardCharsets.ISO_8859_1));
        assertSuccess(endpoint, "application/json;charset=UTF-8", utf8(valid(endpoint, "\u00e9")));
    }

    /** A Content-Type or charset that cannot be parsed: 415. Mutation: problem details disabled. */
    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void aContentTypeThatCannotBeParsedIs415(Endpoint endpoint) throws Exception {
        for (String type : new String[] {"json", "application/", ";;", "application/json;charset=X-NOPE"}) {
            assertShape(endpoint, type, utf8(valid(endpoint, "e")), 415, CANNOT_PARSE, UNSUPPORTED);
        }
    }

    /**
     * Wrong types and broken constraints on create. A number for a string
     * field is taken as text (a merchant named 12345 is created). Mutation:
     * problem details disabled.
     */
    @Test
    void createWrongTypesAndConstraintsAreAnsweredAsBefore() throws Exception {
        String base = "{\"name\":\"Shop\",\"category\":\"Groceries\",\"kycVerified\":true,\"commissionRate\":5.00,"
                + "\"loginEmail\":\"a@example.com\"";
        assertSuccess(Endpoint.CREATE, "application/json", utf8(base.replace("\"Shop\"", "12345") + "}"));
        for (String unreadable : new String[] {base.replace("\"Shop\"", "{}"), base.replace("true", "\"true\""),
                base.replace("5.00", "\"5.00\"")}) {
            assertShape(Endpoint.CREATE, "application/json", utf8(unreadable + "}"), 400, UNREADABLE, BAD);
        }
        for (String invalid : new String[] {base.replace("5.00", "100.01"), base.replace("5.00", "1.005"),
                base.replace("a@example.com", "nope"), base.replace("\"Shop\"", "\"" + "x".repeat(101) + "\""),
                base.replace("a@example.com", "nope") + ",\"note\":1"}) {
            assertShape(Endpoint.CREATE, "application/json", utf8(invalid + "}"), 400, INVALID, BAD);
        }
    }

    /** Wrong types and broken constraints on update. Mutation: problem details disabled. */
    @Test
    void updateWrongTypesAndConstraintsAreAnsweredAsBefore() throws Exception {
        for (String unreadable : new String[] {"{\"commissionRate\":\"5.00\"}", "{\"kycVerified\":\"true\"}",
                "{\"active\":1}"}) {
            assertShape(Endpoint.UPDATE, "application/json", utf8(unreadable), 400, UNREADABLE, BAD);
        }
        assertShape(Endpoint.UPDATE, "application/json", utf8("{\"commissionRate\":100.01}"), 400, INVALID, BAD);
        assertShape(Endpoint.UPDATE, "application/json", utf8("{\"active\":null}"), 400,
                "Field(s) must not be null: active.", BAD);
    }

    /** The retry's body. Unknown fields are ignored there. Mutation: problem details disabled. */
    @Test
    void retryBodiesAreAnsweredAsBefore() throws Exception {
        assertShape(Endpoint.RETRY, "application/json", utf8("{\"email\":\"nope\"}"), 400, INVALID, BAD);
        assertShape(Endpoint.RETRY, "application/json", utf8("{\"email\":\"\"}"), 400, INVALID, BAD);
        assertShape(Endpoint.RETRY, "application/json", utf8("{\"email\":{}}"), 400, UNREADABLE, BAD);
        assertSuccess(Endpoint.RETRY, "application/json", utf8("{\"note\":1}"));
    }

    /**
     * Unknown field names that are named in the answer, and stay so: create
     * sorts them and hints at active; update keeps their order. Mutation:
     * "Unknown field(s): " reworded in CreateMerchantRequest.
     */
    @Test
    void safeUnknownFieldNamesAreNamedAsBefore() throws Exception {
        String base = "{\"name\":\"Shop\",\"category\":\"Groceries\",\"kycVerified\":true,\"commissionRate\":5.00,"
                + "\"loginEmail\":\"a@example.com\"";
        assertShape(Endpoint.CREATE, "application/json", utf8(base + ",\"note\":1}"), 400, "Unknown field(s): note.", BAD);
        assertShape(Endpoint.CREATE, "application/json", utf8(base + ",\"active\":true}"), 400,
                "Unknown field(s): active. A new merchant always starts active.", BAD);
        assertShape(Endpoint.CREATE, "application/json", utf8(base + ",\"zeta\":1,\"alpha\":2}"), 400,
                "Unknown field(s): alpha, zeta.", BAD);
        assertShape(Endpoint.UPDATE, "application/json", utf8("{\"note\":1,\"active\":true}"), 400,
                "Unknown or not updatable field(s): note. Only commissionRate, kycVerified and active can be changed.",
                BAD);
        assertShape(Endpoint.UPDATE, "application/json", utf8("{\"zeta\":1,\"name\":\"x\"}"), 400,
                "Unknown or not updatable field(s): zeta, name. Only commissionRate, kycVerified and active can be"
                        + " changed.", BAD);
    }

    /**
     * Today, any unknown field name is named in the answer, even one that
     * could be a secret (F3 changes these answers, and only these). Mutation:
     * "Unknown field(s): " reworded in CreateMerchantRequest.
     */
    @Test
    void createUnsafeUnknownFieldNames() throws Exception {
        String base = "{\"name\":\"Shop\",\"category\":\"Groceries\",\"kycVerified\":true,\"commissionRate\":5.00,"
                + "\"loginEmail\":\"a@example.com\"";
        assertShape(Endpoint.CREATE, "application/json", utf8(base + ",\"4929117300568841\":1}"), 400,
                "Unknown field(s): 4929117300568841.", BAD);
        assertShape(Endpoint.CREATE, "application/json", utf8(base + ",\"Zq7Pw!Lk9xQ\":1}"), 400,
                "Unknown field(s): Zq7Pw!Lk9xQ.", BAD);
        assertShape(Endpoint.CREATE, "application/json", utf8(base + ",\"" + "a".repeat(42) + "\":1}"), 400,
                "Unknown field(s): " + "a".repeat(42) + ".", BAD);
    }

    /** As create, for update. Mutation: "Unknown or not updatable field(s): " reworded in UpdateMerchantRequest. */
    @Test
    void updateUnsafeUnknownFieldNames() throws Exception {
        assertShape(Endpoint.UPDATE, "application/json", utf8("{\"Zq7Pw!Lk9xQ\":1}"), 400,
                "Unknown or not updatable field(s): Zq7Pw!Lk9xQ. Only commissionRate, kycVerified and active can be"
                        + " changed.", BAD);
        assertShape(Endpoint.UPDATE, "application/json", utf8("{\"a4929117300568841\":1}"), 400,
                "Unknown or not updatable field(s): a4929117300568841. Only commissionRate, kycVerified and active can"
                        + " be changed.", BAD);
    }

    /**
     * Another method on the same path: 405 with Allow (its methods in any
     * order), and 401 before that without a token; no token on the endpoint
     * itself is a bare 401. Mutation: problem details disabled.
     */
    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void anotherMethodIs405AndNoTokenIs401(Endpoint endpoint) throws Exception {
        String path = pathOf(endpoint);
        String allow = switch (endpoint) {
            case CREATE -> "GET, POST";
            case RETRY -> "POST";
            case UPDATE -> "GET, PATCH";
        };
        for (HttpMethod other : new HttpMethod[] {HttpMethod.POST, HttpMethod.PATCH, HttpMethod.PUT, HttpMethod.DELETE}) {
            if (other.equals(methodOf(endpoint))) {
                continue;
            }
            MockHttpServletResponse response = send(other, path, "application/json", utf8(valid(endpoint, "e")), true);
            assertThat(response.getStatus()).isEqualTo(405);
            assertThat(response.getContentAsString()).isEqualTo(
                    problem("Method '" + other.name() + "' is not supported.", path, 405, "Method Not Allowed"));
            assertThat(response.getHeader("Allow").split(", ")).containsExactlyInAnyOrder(allow.split(", "));
            MockHttpServletResponse noToken = send(other, path, "application/json", utf8(valid(endpoint, "e")), false);
            assertThat(noToken.getStatus()).isEqualTo(401);
            assertThat(noToken.getContentAsString()).isEmpty();
        }
        MockHttpServletResponse noToken = send(methodOf(endpoint), path, "application/json", utf8(valid(endpoint, "e")),
                false);
        assertThat(noToken.getStatus()).isEqualTo(401);
        assertThat(noToken.getContentAsString()).isEmpty();
    }
}

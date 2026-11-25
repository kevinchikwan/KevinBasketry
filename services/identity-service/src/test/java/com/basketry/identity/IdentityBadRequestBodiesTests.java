package com.basketry.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.basketry.identity.account.AccountRegistrationService;
import com.basketry.identity.account.AccountRepository;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Today's exact 400, 401 and 415 answers of every identity-service endpoint
 * with a body, byte for byte: status, content type and body. Written before
 * the bodies are read by the controllers (ROADMAP F2), which must leave every
 * one of them unchanged. Each answer is Spring Boot's problem detail, its
 * members in the order Boot writes them.
 */
@SpringBootTest(properties = "basketry.internal.api-key=" + IdentityBadRequestBodiesTests.KEY)
@AutoConfigureMockMvc
class IdentityBadRequestBodiesTests {

    static final String KEY = "test-only-identity-bodies-key-not-a-secret";

    private static final String PROBLEM = "application/problem+json";
    private static final String UNREADABLE = "Failed to read request";
    private static final String INVALID = "Invalid request content.";
    private static final String TEXT_PLAIN = "Content-Type 'text/plain;charset=UTF-8' is not supported.";
    private static final String NO_TYPE = "Content-Type 'application/octet-stream' is not supported.";
    private static final String GOOD_PASSWORD = "Corr3ct-Horse!";
    private static final String WEAK = "Password must be at least 8 characters. Password must contain an uppercase"
            + " letter (A-Z). Password must contain a digit (0-9). Password must contain a special character from"
            + " !@#$%^&*()-_=+.";
    private static final String WEAK_LIST = "[\"Password must be at least 8 characters.\",\"Password must contain an"
            + " uppercase letter (A-Z).\",\"Password must contain a digit (0-9).\",\"Password must contain a special"
            + " character from !@#$%^&*()-_=+.\"]";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountRegistrationService registration;

    private long targetId;
    private String adminToken;
    private String buyerToken;

    @BeforeEach
    void accountsAndTokens() throws Exception {
        accounts.deleteAll();
        targetId = registration.registerBuyer("target@example.com", GOOD_PASSWORD).getId();
        adminToken = token("admin-placeholder@basketry.example", "ChangeMe-Before-Deploy!");
        buyerToken = token("target@example.com", GOOD_PASSWORD);
    }

    private String token(String email, String password) throws Exception {
        String body = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static String problem(String detail, String instance, int status, String title) {
        return "{\"detail\":\"" + detail + "\",\"instance\":\"" + instance + "\",\"status\":" + status
                + ",\"title\":\"" + title + "\"}";
    }

    /** One request: a name, its content type (null for none), its body, and the exact answer expected. */
    private static Arguments answer(String name, String contentType, String body, int status, String expected) {
        return Arguments.of(name, contentType, body, status, expected);
    }

    /** The cases every endpoint shares: unreadable JSON and a content type that is not JSON. */
    private static Stream<Arguments> common(String path) {
        return Stream.of(
                answer("unreadable JSON", "application/json", "{x", 400, problem(UNREADABLE, path, 400, "Bad Request")),
                answer("an empty body", "application/json", "", 400, problem(UNREADABLE, path, 400, "Bad Request")),
                answer("the JSON literal null", "application/json", "null", 400,
                        problem(UNREADABLE, path, 400, "Bad Request")),
                answer("a trailing token", "application/json", "{\"a\":1} x", 400,
                        problem(UNREADABLE, path, 400, "Bad Request")),
                answer("an array", "application/json", "[]", 400, problem(UNREADABLE, path, 400, "Bad Request")),
                answer("text/plain", "text/plain", "{}", 415, problem(TEXT_PLAIN, path, 415, "Unsupported Media Type")),
                answer("no content type", null, "{}", 415, problem(NO_TYPE, path, 415, "Unsupported Media Type")));
    }

    private void assertAnswer(MockHttpServletRequestBuilder request, String contentType, String body, int status,
                              String expected) throws Exception {
        if (contentType != null) {
            request.contentType(contentType);
        }
        MockHttpServletResponse response = mvc.perform(request.content(body)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentAsString()).isEqualTo(expected);
        assertThat(response.getContentType()).isEqualTo(expected.isEmpty() ? null : PROBLEM);
    }

    static Stream<Arguments> register() {
        String path = "/accounts/register";
        String invalid = problem(INVALID, path, 400, "Bad Request");
        return Stream.concat(common(path), Stream.of(
                answer("the email as an object", "application/json", "{\"email\":{},\"password\":\"x\"}", 400,
                        problem(UNREADABLE, path, 400, "Bad Request")),
                answer("an invalid email", "application/json", "{\"email\":\"nope\",\"password\":\"x\"}", 400, invalid),
                answer("no email", "application/json", "{\"password\":\"x\"}", 400, invalid),
                answer("no password", "application/json", "{\"email\":\"a@example.com\"}", 400, invalid),
                answer("both invalid", "application/json", "{\"email\":\"nope\"}", 400, invalid)));
    }

    /** Mutation: {@code spring.mvc.problemdetails.enabled: false}. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("register")
    void registerAnswersAreUnchanged(String name, String contentType, String body, int status, String expected)
            throws Exception {
        assertAnswer(post("/accounts/register"), contentType, body, status, expected);
    }

    static Stream<Arguments> login() {
        String path = "/auth/login";
        return Stream.concat(common(path), Stream.of(
                answer("the password as an object", "application/json", "{\"email\":\"a@example.com\",\"password\":{}}",
                        400, problem(UNREADABLE, path, 400, "Bad Request")),
                answer("a blank email", "application/json", "{\"email\":\"\",\"password\":\"x\"}", 400,
                        problem(INVALID, path, 400, "Bad Request")),
                answer("a wrong password", "application/json",
                        "{\"email\":\"target@example.com\",\"password\":\"Wrong-Horse1!\"}", 401,
                        problem("Invalid email or password.", path, 401, "Unauthorized"))));
    }

    /** Mutation: {@code spring.mvc.problemdetails.enabled: false}. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("login")
    void loginAnswersAreUnchanged(String name, String contentType, String body, int status, String expected)
            throws Exception {
        assertAnswer(post("/auth/login"), contentType, body, status, expected);
    }

    static Stream<Arguments> buyerStatus() {
        String path = "/accounts/{id}/buyer-status";
        return Stream.concat(common(path), Stream.of(
                answer("a string", "application/json", "{\"buyerActive\":\"yes\"}", 400,
                        problem(UNREADABLE, path, 400, "Bad Request")),
                answer("no buyerActive", "application/json", "{}", 400, problem(INVALID, path, 400, "Bad Request"))));
    }

    /** Admin's token. Mutation: {@code spring.mvc.problemdetails.enabled: false}. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("buyerStatus")
    void buyerStatusAnswersAreUnchanged(String name, String contentType, String body, int status, String expected)
            throws Exception {
        assertAnswer(patch("/accounts/{id}/buyer-status", targetId).header("Authorization", "Bearer " + adminToken),
                contentType, body, status, expected.replace("{id}", Long.toString(targetId)));
    }

    /**
     * The body is checked before the admin check: a buyer's broken body is a
     * 400, not a 403; and no token at all is a bare 401 before the body.
     * Mutation: {@code spring.mvc.problemdetails.enabled: false}.
     */
    @Test
    void buyerStatusChecksTheBodyBeforeAdminAndTheTokenBeforeTheBody() throws Exception {
        String path = "/accounts/" + targetId + "/buyer-status";
        assertAnswer(patch(path).header("Authorization", "Bearer " + buyerToken), "application/json", "{x", 400,
                problem(UNREADABLE, path, 400, "Bad Request"));
        assertAnswer(patch(path).header("Authorization", "Bearer " + buyerToken), "application/json", "{}", 400,
                problem(INVALID, path, 400, "Bad Request"));
        assertAnswer(patch(path), "application/json", "{x", 401, "");
    }

    static Stream<Arguments> merchantLink() {
        String path = "/internal/accounts/merchant-link";
        String invalid = problem(INVALID, path, 400, "Bad Request");
        return Stream.concat(common(path), Stream.of(
                answer("merchantId as a string", "application/json",
                        "{\"email\":\"m@example.com\",\"merchantId\":\"5\",\"initialPassword\":\"" + GOOD_PASSWORD + "\"}",
                        400, problem(UNREADABLE, path, 400, "Bad Request")),
                answer("a negative merchantId", "application/json",
                        "{\"email\":\"m@example.com\",\"merchantId\":-1,\"initialPassword\":\"" + GOOD_PASSWORD + "\"}",
                        400, invalid),
                answer("an invalid email", "application/json",
                        "{\"email\":\"nope\",\"merchantId\":5,\"initialPassword\":\"" + GOOD_PASSWORD + "\"}", 400, invalid)));
    }

    /** With the key. Mutation: {@code spring.mvc.problemdetails.enabled: false}. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("merchantLink")
    void merchantLinkAnswersAreUnchanged(String name, String contentType, String body, int status, String expected)
            throws Exception {
        assertAnswer(post("/internal/accounts/merchant-link").header("X-Internal-Api-Key", KEY),
                contentType, body, status, expected);
    }

    /** No key: a bare 401 before the body. Mutation: the internal chain's entry point answering 403. */
    @Test
    void merchantLinkWithoutTheKeyIsABare401() throws Exception {
        assertAnswer(post("/internal/accounts/merchant-link"), "application/json", "{x", 401, "");
    }

    // ---- request shapes (added in F2 part 2, each proven against e4ff3b2's code first) ----

    private static final String CANNOT_PARSE = "Could not parse Content-Type.";
    private static final AtomicInteger UNIQUE = new AtomicInteger();

    enum Endpoint { REGISTER, LOGIN, BUYER_STATUS, MERCHANT_LINK }

    /** The endpoint's own method and path, with the buyer status path for {@code targetId}. */
    private String pathOf(Endpoint endpoint) {
        return switch (endpoint) {
            case REGISTER -> "/accounts/register";
            case LOGIN -> "/auth/login";
            case BUYER_STATUS -> "/accounts/" + targetId + "/buyer-status";
            case MERCHANT_LINK -> "/internal/accounts/merchant-link";
        };
    }

    private static HttpMethod methodOf(Endpoint endpoint) {
        return endpoint == Endpoint.BUYER_STATUS ? HttpMethod.PATCH : HttpMethod.POST;
    }

    /** A body each endpoint accepts; {@code text} is put into the password (register, link) to test charsets. */
    private static String valid(Endpoint endpoint, String passwordEnd) {
        int n = UNIQUE.incrementAndGet();
        return switch (endpoint) {
            case REGISTER -> "{\"email\":\"shape" + n + "@example.com\",\"password\":\"Corr3ct-Hors" + passwordEnd + "!\"}";
            case LOGIN -> "{\"email\":\"target@example.com\",\"password\":\"Corr3ct-Hors" + passwordEnd + "!\"}";
            case BUYER_STATUS -> "{\"buyerActive\":true}";
            case MERCHANT_LINK -> "{\"email\":\"shape" + n + "@example.com\",\"merchantId\":" + (1000 + n)
                    + ",\"initialPassword\":\"Corr3ct-Hors" + passwordEnd + "!\"}";
        };
    }

    /** The success each endpoint answers a valid body with. */
    private static int ok(Endpoint endpoint) {
        return switch (endpoint) {
            case REGISTER, MERCHANT_LINK -> 201;
            case LOGIN, BUYER_STATUS -> 200;
        };
    }

    private void authorize(Endpoint endpoint, MockHttpServletRequestBuilder request) {
        if (endpoint == Endpoint.BUYER_STATUS) {
            request.header("Authorization", "Bearer " + adminToken);
        } else if (endpoint == Endpoint.MERCHANT_LINK) {
            request.header("X-Internal-Api-Key", KEY);
        }
    }

    /**
     * Sends {@code body} with the raw {@code Content-Type} header (none if null)
     * and checks the status, and for an error the exact problem detail. MockMvc
     * adds the request's character encoding (UTF-8) to a type without a charset,
     * as Spring then reports it ("text/plain;charset=UTF-8").
     */
    private void assertShape(Endpoint endpoint, HttpMethod method, String contentType, byte[] body, int status,
                             String detail, String title) throws Exception {
        String path = pathOf(endpoint);
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path);
        authorize(endpoint, request);
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (body != null) {
            request.content(body);
        }
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(status);
        if (detail != null) {
            assertThat(response.getContentAsString()).isEqualTo(problem(detail, path, status, title));
            assertThat(response.getContentType()).isEqualTo(PROBLEM);
        }
    }

    private void assertShape(Endpoint endpoint, String contentType, byte[] body, int status, String detail,
                             String title) throws Exception {
        assertShape(endpoint, methodOf(endpoint), contentType, body, status, detail, title);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * An empty body: a 400 without a Content-Type, but a 415 with an unsupported
     * one (Spring answers 415 for any unsupported type unless there is neither a
     * type nor a body). A body of only whitespace is not empty. Mutation: the
     * content-type check put back before the empty check (no Content-Type and
     * no body: a 415).
     */
    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void anEmptyOrBlankBodyIsAnsweredAsBefore(Endpoint endpoint) throws Exception {
        assertShape(endpoint, null, null, 400, UNREADABLE, "Bad Request");
        assertShape(endpoint, "text/plain", null, 415, TEXT_PLAIN, "Unsupported Media Type");
        assertShape(endpoint, "application/json", null, 400, UNREADABLE, "Bad Request");
        assertShape(endpoint, "application/json", utf8("   "), 400, UNREADABLE, "Bad Request");
        assertShape(endpoint, "text/plain", utf8("   "), 415, TEXT_PLAIN, "Unsupported Media Type");
    }

    /**
     * A valid body under a content type that is not JSON, or none: 415 naming
     * the type as Spring read it. Mutation: the content-type check removed.
     */
    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void aValidBodyUnderAnotherTypeIs415(Endpoint endpoint) throws Exception {
        assertShape(endpoint, null, utf8(valid(endpoint, "e")), 415, NO_TYPE, "Unsupported Media Type");
        assertShape(endpoint, "application/xml", utf8(valid(endpoint, "e")), 415,
                "Content-Type 'application/xml;charset=UTF-8' is not supported.", "Unsupported Media Type");
        assertShape(endpoint, "text/plain", utf8(valid(endpoint, "e")), 415, TEXT_PLAIN, "Unsupported Media Type");
    }

    /**
     * Any JSON type is read, with or without a charset; a non-Unicode charset
     * decodes the body (an ISO-8859-1 e-acute is a valid password character for
     * register and link, and a wrong password for login, so a 401); a Unicode
     * one is left to Jackson's own detection, so UTF-8 bytes labelled UTF-16
     * are read too. Mutation: the body always read as bytes, the charset
     * ignored (the ISO-8859-1 e-acute is then not UTF-8: a 400).
     */
    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void everyJsonTypeAndCharsetIsReadAsBefore(Endpoint endpoint) throws Exception {
        int ok = ok(endpoint);
        assertShape(endpoint, "application/json;charset=UTF-8", utf8(valid(endpoint, "e")), ok, null, null);
        assertShape(endpoint, "application/json;charset=ISO-8859-1",
                valid(endpoint, "e").getBytes(StandardCharsets.ISO_8859_1), ok, null, null);
        assertShape(endpoint, "application/json;charset=UTF-16",
                valid(endpoint, "e").getBytes(StandardCharsets.UTF_16), ok, null, null);
        assertShape(endpoint, "application/json;charset=UTF-16", utf8(valid(endpoint, "e")), ok, null, null);
        assertShape(endpoint, "application/vnd.x+json", utf8(valid(endpoint, "e")), ok, null, null);
        int latin = endpoint == Endpoint.LOGIN ? 401 : ok;
        assertShape(endpoint, "application/json;charset=ISO-8859-1",
                valid(endpoint, "\u00e9").getBytes(StandardCharsets.ISO_8859_1), latin,
                latin == 401 ? "Invalid email or password." : null, latin == 401 ? "Unauthorized" : null);
        assertShape(endpoint, "application/json;charset=UTF-8", utf8(valid(endpoint, "\u00e9")), latin,
                latin == 401 ? "Invalid email or password." : null, latin == 401 ? "Unauthorized" : null);
    }

    /**
     * A Content-Type that cannot be parsed, or an unknown charset: 415 "Could not
     * parse Content-Type.", answered by Spring's own argument resolver before the
     * controller is called. Mutation: problem details disabled.
     */
    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void aContentTypeThatCannotBeParsedIs415(Endpoint endpoint) throws Exception {
        for (String type : new String[] {"json", "application/", ";;", "application/json;charset=X-NOPE"}) {
            assertShape(endpoint, type, utf8(valid(endpoint, "e")), 415, CANNOT_PARSE, "Unsupported Media Type");
        }
    }

    /** {} where fields are required. Mutation: validation skipped. */
    @ParameterizedTest
    @EnumSource(value = Endpoint.class, names = {"REGISTER", "LOGIN", "MERCHANT_LINK"})
    void anEmptyObjectIsInvalidContent(Endpoint endpoint) throws Exception {
        assertShape(endpoint, "application/json", utf8("{}"), 400, INVALID, "Bad Request");
    }

    /**
     * Another method on the same path: 405 with Allow (its methods in any order:
     * Spring does not keep the order stable), behind the same security as before. /accounts/register also allows GET, because GET /accounts/{id}
     * matches it. Without a token, PATCH on the public POST paths is a 401: the
     * security chain only opens POST. Mutation: problem details disabled.
     */
    @Test
    void anotherMethodIs405AsBefore() throws Exception {
        assertMethod(Endpoint.REGISTER, HttpMethod.PATCH, true, 405, "Method 'PATCH' is not supported.", "POST, GET");
        assertMethod(Endpoint.REGISTER, HttpMethod.PUT, true, 405, "Method 'PUT' is not supported.", "POST, GET");
        assertMethod(Endpoint.REGISTER, HttpMethod.PATCH, false, 401, null, null);
        assertMethod(Endpoint.LOGIN, HttpMethod.PATCH, true, 405, "Method 'PATCH' is not supported.", "POST");
        assertMethod(Endpoint.LOGIN, HttpMethod.PUT, true, 405, "Method 'PUT' is not supported.", "POST");
        assertMethod(Endpoint.LOGIN, HttpMethod.PATCH, false, 401, null, null);
        assertMethod(Endpoint.BUYER_STATUS, HttpMethod.POST, true, 405, "Method 'POST' is not supported.", "PATCH");
        assertMethod(Endpoint.BUYER_STATUS, HttpMethod.PUT, true, 405, "Method 'PUT' is not supported.", "PATCH");
        assertMethod(Endpoint.BUYER_STATUS, HttpMethod.POST, false, 401, null, null);
        assertMethod(Endpoint.MERCHANT_LINK, HttpMethod.PATCH, true, 405, "Method 'PATCH' is not supported.", "POST");
        assertMethod(Endpoint.MERCHANT_LINK, HttpMethod.PUT, true, 405, "Method 'PUT' is not supported.", "POST");
        assertMethod(Endpoint.MERCHANT_LINK, HttpMethod.PATCH, false, 401, null, null);
    }

    /** {@code credentials}: the admin token for every path but the internal one, which gets the key. */
    private void assertMethod(Endpoint endpoint, HttpMethod method, boolean credentials, int status, String detail,
                              String allow) throws Exception {
        String path = pathOf(endpoint);
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path)
                .contentType("application/json").content(valid(endpoint, "e"));
        if (credentials) {
            if (endpoint == Endpoint.MERCHANT_LINK) {
                request.header("X-Internal-Api-Key", KEY);
            } else {
                request.header("Authorization", "Bearer " + adminToken);
            }
        }
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentAsString())
                .isEqualTo(detail == null ? "" : problem(detail, path, status, "Method Not Allowed"));
        // The methods in Allow, not their order: Spring does not keep it stable from run to run.
        if (allow == null) {
            assertThat(response.getHeader("Allow")).isNull();
        } else {
            assertThat(response.getHeader("Allow").split(", ")).containsExactlyInAnyOrder(allow.split(", "));
        }
    }

    /**
     * The policy's 400, from the service after the body is read, on both
     * endpoints that take a new password. Mutation: the violations joined with
     * two spaces in WeakPasswordException's detail.
     */
    @Test
    void aWeakPasswordAnswerIsUnchanged() throws Exception {
        String register = "/accounts/register";
        String link = "/internal/accounts/merchant-link";
        assertAnswer(post(register), "application/json", "{\"email\":\"a@example.com\",\"password\":\"short\"}", 400,
                "{\"detail\":\"" + WEAK + "\",\"instance\":\"" + register + "\",\"status\":400,\"title\":\"Password"
                        + " does not meet the policy\",\"violations\":" + WEAK_LIST + "}");
        assertAnswer(post(link).header("X-Internal-Api-Key", KEY), "application/json",
                "{\"email\":\"m2@example.com\",\"merchantId\":8,\"initialPassword\":\"short\"}", 400,
                "{\"detail\":\"" + WEAK + "\",\"instance\":\"" + link + "\",\"status\":400,\"title\":\"Password"
                        + " does not meet the policy\",\"violations\":" + WEAK_LIST + "}");
    }
}

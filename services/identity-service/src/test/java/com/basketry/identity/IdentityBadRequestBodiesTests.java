package com.basketry.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.basketry.identity.account.AccountRegistrationService;
import com.basketry.identity.account.AccountRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
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

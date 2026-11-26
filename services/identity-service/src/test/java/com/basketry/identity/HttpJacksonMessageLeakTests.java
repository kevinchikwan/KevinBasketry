package com.basketry.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.identity.account.Account;
import com.basketry.identity.account.AccountRegistrationService;
import com.basketry.identity.account.AccountRepository;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * A password and an email over HTTP (ROADMAP F, found by order-service's F1
 * and promotion-service's A6): when Spring reads a @RequestBody, a Jackson
 * error reaches Spring's DEBUG and TRACE lines with Jackson's message, which
 * can quote the input, and a failed @Valid check is logged with its rejected
 * value. Each case: no log line and no answer holds a sentinel, while
 * Spring's web layer logs at TRACE and Tomcat and our own loggers at DEBUG.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "logging.level.org.springframework.web=TRACE",
        "logging.level.org.apache.tomcat=DEBUG",
        "logging.level.org.apache.coyote=DEBUG",
        "logging.level.org.apache.catalina=DEBUG",
        "logging.level.com.basketry=DEBUG",
        "basketry.internal.api-key=" + HttpJacksonMessageLeakTests.KEY,
        // Its own context, so the log is bound to this class's output capture.
        "basketry.test.context=jackson-message-leak"})
class HttpJacksonMessageLeakTests {

    static final String KEY = "test-only-identity-leak-key-not-a-secret";

    /** A password that meets the policy (8 to 15, upper, lower, digit, special). */
    private static final String PASSWORD = "Zq7Pw!Lk9xQ";
    /** Too short: 7 characters. Its text is also the start of PASSWORD. */
    private static final String SHORT_PASSWORD = "Zq7Pw!L";
    /** Breaks the policy: no special character. */
    private static final String PLAIN_PASSWORD = "Zq7PwXLk9xQ";
    private static final String NUMBER = "7319046628155";
    private static final String EMAIL_HEAD = "leak.sentinel.7q";
    private static final List<String> SENTINELS = List.of(SHORT_PASSWORD, "Zq7PwXL", "731904662815", EMAIL_HEAD);

    private static final String ADMIN_EMAIL = "admin-placeholder@basketry.example";
    private static final String ADMIN_PASSWORD = "ChangeMe-Before-Deploy!";
    private static final AtomicInteger EMAILS = new AtomicInteger();

    enum Caller { NOBODY, ADMIN, INTERNAL }

    @LocalServerPort
    private int port;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountRegistrationService registration;

    private final HttpClient client = HttpClient.newHttpClient();

    private long targetId;

    @BeforeEach
    void aBuyerToChange() {
        accounts.deleteAll();
        Account target = registration.registerBuyer("target@example.com", "Corr3ct-Horse!");
        targetId = target.getId();
    }

    /** A fresh email holding the sentinel, so a valid body is never a 409. */
    private static String email() {
        return EMAIL_HEAD + "+" + EMAILS.incrementAndGet() + "@example.com";
    }

    private static String q(String text) {
        return "\"" + text + "\"";
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                // POST /accounts/register
                register("password as a JSON number", "{\"email\":" + q(email()) + ",\"password\":" + NUMBER + "}"),
                register("email as a JSON number", "{\"email\":" + NUMBER + ",\"password\":" + q(PASSWORD) + "}"),
                register("email as an object", "{\"email\":{\"x\":" + q(email()) + "},\"password\":" + q(PASSWORD) + "}"),
                register("the password as a repeated field name",
                        "{\"" + PASSWORD + "\":1,\"" + PASSWORD + "\":2,\"email\":" + q(email()) + ",\"password\":\"x\"}"),
                register("the password repeated inside an unknown field",
                        "{\"x\":{\"" + PASSWORD + "\":1,\"" + PASSWORD + "\":2},\"email\":" + q(email()) + ",\"password\":\"x\"}"),
                register("the password as an unknown field name",
                        "{\"" + PASSWORD + "\":1,\"email\":" + q(email()) + ",\"password\":\"x\"}"),
                register("the password as an unknown field's value",
                        "{\"note\":" + q(PASSWORD) + ",\"email\":" + q(email()) + ",\"password\":\"x\"}"),
                register("the password as a trailing token",
                        "{\"email\":" + q(email()) + ",\"password\":\"x\"} " + q(PASSWORD)),
                register("a real body followed by a trailing token",
                        "{\"email\":" + q(email()) + ",\"password\":" + q(PASSWORD) + "} x"),
                register("a password that is too short (policy)",
                        "{\"email\":" + q(email()) + ",\"password\":" + q(SHORT_PASSWORD) + "}"),
                register("a password with no special character (policy)",
                        "{\"email\":" + q(email()) + ",\"password\":" + q(PLAIN_PASSWORD) + "}"),
                register("an invalid email (@Valid)",
                        "{\"email\":" + q(EMAIL_HEAD + "-example.com") + ",\"password\":" + q(PASSWORD) + "}"),
                register("a valid registration", "{\"email\":" + q(email()) + ",\"password\":" + q(PASSWORD) + "}"),
                // POST /auth/login
                login("login: password as a JSON number", "{\"email\":" + q(email()) + ",\"password\":" + NUMBER + "}"),
                login("login: email as an object", "{\"email\":{\"x\":" + q(email()) + "},\"password\":" + q(PASSWORD) + "}"),
                login("login: a real body followed by a trailing token",
                        "{\"email\":" + q(email()) + ",\"password\":" + q(PASSWORD) + "} x"),
                login("login: password missing (@Valid)", "{\"email\":" + q(email()) + "}"),
                login("login: a wrong password", "{\"email\":" + q(email()) + ",\"password\":" + q(PASSWORD) + "}"),
                // PATCH /accounts/{id}/buyer-status (Admin)
                Arguments.of("buyer status as a string", Caller.ADMIN, "PATCH", "buyer-status",
                        "{\"buyerActive\":" + q(PASSWORD) + "}"),
                Arguments.of("buyer status as a number", Caller.ADMIN, "PATCH", "buyer-status",
                        "{\"buyerActive\":" + NUMBER + "}"),
                Arguments.of("buyer status followed by a trailing token", Caller.ADMIN, "PATCH", "buyer-status",
                        "{\"buyerActive\":true} " + q(PASSWORD)),
                // POST /internal/accounts/merchant-link (internal key)
                link("merchant link: merchantId as a string",
                        "{\"email\":" + q(email()) + ",\"merchantId\":" + q(NUMBER) + ",\"initialPassword\":" + q(PASSWORD) + "}"),
                link("merchant link: initialPassword as a JSON number",
                        "{\"email\":" + q(email()) + ",\"merchantId\":5,\"initialPassword\":" + NUMBER + "}"),
                link("merchant link: an invalid email (@Valid)",
                        "{\"email\":" + q(EMAIL_HEAD + "-example.com") + ",\"merchantId\":5,\"initialPassword\":" + q(PASSWORD) + "}"),
                link("merchant link: a negative merchantId (@Valid)",
                        "{\"email\":" + q(email()) + ",\"merchantId\":-" + NUMBER + ",\"initialPassword\":" + q(PASSWORD) + "}"),
                link("merchant link: a password that breaks the policy",
                        "{\"email\":" + q(email()) + ",\"merchantId\":6,\"initialPassword\":" + q(PLAIN_PASSWORD) + "}"),
                link("merchant link: a real body followed by a trailing token",
                        "{\"email\":" + q(email()) + ",\"merchantId\":7,\"initialPassword\":" + q(PASSWORD) + "} x"));
    }

    private static Arguments register(String name, String body) {
        return Arguments.of(name, Caller.NOBODY, "POST", "/accounts/register", body);
    }

    private static Arguments login(String name, String body) {
        return Arguments.of(name, Caller.NOBODY, "POST", "/auth/login", body);
    }

    private static Arguments link(String name, String body) {
        return Arguments.of(name, Caller.INTERNAL, "POST", "/internal/accounts/merchant-link", body);
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Successful answers: Spring logs the object it writes at TRACE ("Writing
     * [...]"). A registration, a login, the account read with its own token, the
     * Admin list, a merchant link that creates an account and one that links
     * the existing account. The answers hold the email by design; no log line
     * may hold it, and nothing may hold the password. Mutations: AccountResponse's
     * toString with the email; MerchantLinkResponse's toString with the email.
     */
    @org.junit.jupiter.api.Test
    void successfulAnswersPutNoSentinelInALogLine(CapturedOutput output) throws Exception {
        String registered = EMAIL_HEAD + "+ok1@example.com";
        String linked = EMAIL_HEAD + "+ok2@example.com";
        List<HttpResponse<String>> responses = new java.util.ArrayList<>();

        HttpResponse<String> register = send("POST", "/accounts/register",
                "{\"email\":" + q(registered) + ",\"password\":" + q(PASSWORD) + "}");
        responses.add(register);
        String id = register.headers().firstValue("Location").orElseThrow().replaceAll(".*/", "");
        HttpResponse<String> login = send("POST", "/auth/login",
                "{\"email\":" + q(registered) + ",\"password\":" + q(PASSWORD) + "}");
        responses.add(login);
        String token = JsonPath.read(login.body(), "$.accessToken");
        responses.add(client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/accounts/" + id))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString()));
        String admin = JsonPath.read(send("POST", "/auth/login", "{\"email\":" + q(ADMIN_EMAIL) + ",\"password\":"
                + q(ADMIN_PASSWORD) + "}").body(), "$.accessToken");
        responses.add(client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/accounts"))
                .header("Authorization", "Bearer " + admin).GET().build(), HttpResponse.BodyHandlers.ofString()));
        responses.add(send("POST", "/internal/accounts/merchant-link", "{\"email\":" + q(linked)
                + ",\"merchantId\":41,\"initialPassword\":" + q(PASSWORD) + "}", "X-Internal-Api-Key", KEY));
        responses.add(send("POST", "/internal/accounts/merchant-link", "{\"email\":" + q(registered)
                + ",\"merchantId\":42}", "X-Internal-Api-Key", KEY));

        assertThat(responses).extracting(HttpResponse::statusCode).containsExactly(201, 200, 200, 200, 201, 200);
        // The check would be empty if the logging were off: the answers are written at TRACE.
        assertThat(output).contains("Writing [AccountResponse[").contains("Writing [MerchantLinkResponse[")
                .contains("Writing [AccountPage[");
        List<String> leaks = new java.util.ArrayList<>();
        for (String sentinel : SENTINELS) {
            output.getAll().lines().filter(line -> line.contains(sentinel))
                    .forEach(line -> leaks.add("log:" + sentinel + " :: " + line));
        }
        for (HttpResponse<String> response : responses) {
            if (response.body().contains(SHORT_PASSWORD)) {
                leaks.add("answer:password :: " + response.body());
            }
        }
        assertThat(leaks).isEmpty();
    }

    /**
     * Mutations, one per controller: the body read by Spring again
     * ({@code @Valid @RequestBody} with the request type) in AccountController's
     * register, in its buyer status, in AuthController and in
     * MerchantLinkController; and RequestBodyReader passing Jackson's message on.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void noSentinelIsInALogLineOrAnAnswer(String name, Caller caller, String method, String path, String body,
                                          CapturedOutput output) throws Exception {
        HttpResponse<String> response = switch (caller) {
            case NOBODY -> send(method, path, body);
            case ADMIN -> {
                String login = send("POST", "/auth/login", "{\"email\":" + q(ADMIN_EMAIL) + ",\"password\":"
                        + q(ADMIN_PASSWORD) + "}").body();
                String token = JsonPath.read(login, "$.accessToken");
                yield send(method, "/accounts/" + targetId + "/buyer-status", body, "Authorization", "Bearer " + token);
            }
            case INTERNAL -> send(method, path, body, "X-Internal-Api-Key", KEY);
        };

        // The check would be empty if the logging were off.
        assertThat(output).contains("Completed " + response.statusCode());
        List<String> leaks = new java.util.ArrayList<>();
        for (String sentinel : SENTINELS) {
            if (response.body().contains(sentinel)) {
                leaks.add("answer:" + sentinel);
            }
            output.getAll().lines().filter(line -> line.contains(sentinel))
                    .forEach(line -> leaks.add("log:" + sentinel + " :: " + line));
        }
        assertThat(leaks).as("status %d", response.statusCode()).isEmpty();
    }
}

package com.basketry.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.basketry.merchant.merchant.Merchant;
import com.basketry.merchant.merchant.MerchantRepository;
import com.basketry.merchant.outbox.OutboxRepository;
import com.basketry.merchant.support.FakeIdentityServer;
import com.basketry.merchant.support.MockIdentityConfig;
import com.basketry.merchant.support.TestTokens;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;

/**
 * A login email, an initial password and a merchant name over HTTP (ROADMAP F,
 * as identity-service's F2): when Spring reads a @RequestBody, a Jackson error
 * reaches Spring's DEBUG and TRACE lines with Jackson's message, which can
 * quote the input, a failed @Valid check is logged with its rejected value,
 * and the request object is logged at TRACE. Each case: no log line and no
 * answer holds a sentinel, while Spring's web layer logs at TRACE and Tomcat
 * and our own loggers at DEBUG.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockIdentityConfig.class)
@TestPropertySource(properties = {
        "logging.level.org.springframework.web=TRACE",
        "logging.level.org.apache.tomcat=DEBUG",
        "logging.level.org.apache.coyote=DEBUG",
        "logging.level.org.apache.catalina=DEBUG",
        "logging.level.com.basketry=DEBUG",
        "basketry.internal.api-key=test-only-internal-key-not-a-secret-000",
        "basketry.identity.retry-delay=10ms",
        // Its own context, so the log is bound to this class's output capture.
        "basketry.test.context=jackson-message-leak"})
class HttpJacksonMessageLeakTests {

    private static final String PASSWORD = "Zq7Pw!Lk9xQ";
    /** No punctuation, so Jackson quotes it whole when it is sent unquoted ("Unrecognized token"). */
    private static final String PLAIN_PASSWORD = "Zq7PwLk9xQr";
    private static final String NAME = "Sentinelname Qx7";
    private static final String NUMBER = "7319046628155";
    private static final String EMAIL_HEAD = "leak.sentinel.7q";
    private static final List<String> SENTINELS =
            List.of("Zq7Pw!L", "Zq7PwLk", "Sentinelname", "731904662815", EMAIL_HEAD);
    /** What a successful answer holds by design: the merchant's own name and login email. */
    private static final List<String> OWN_DATA = List.of("Sentinelname", "731904662815", EMAIL_HEAD);
    private static final AtomicInteger EMAILS = new AtomicInteger();

    enum Target { CREATE, RETRY, UPDATE }

    @DynamicPropertySource
    static void identityServer(DynamicPropertyRegistry registry) {
        registry.add("basketry.identity.base-url", () -> FakeIdentityServer.shared().baseUrl());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private MockRestServiceServer identity;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();

    private String pendingCode;
    private String linkedCode;

    @BeforeEach
    void merchantsAndIdentity() {
        identity.reset();
        // Any case may or may not get as far as identity-service: answer it if it does.
        identity.expect(ExpectedCount.between(0, 5), requestTo(MockIdentityConfig.MERCHANT_LINK_URL))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":7,\"buyerActive\":true,\"merchantActive\":true,\"outcome\":\"CREATED\"}"));
        outbox.deleteAll();
        merchants.deleteAll();
        pendingCode = save("pending@example.com").getMerchantCode();
        Merchant linked = save("linked@example.com");
        jdbc.update("UPDATE merchant SET account_link_status = 'LINKED' WHERE id = ?", linked.getId());
        linkedCode = linked.getMerchantCode();
    }

    private Merchant save(String loginEmail) {
        return merchants.save(new Merchant(merchants.nextId(), "Corner Shop", "Groceries", true,
                new BigDecimal("5.00"), loginEmail, Instant.now()));
    }

    private static String email() {
        return EMAIL_HEAD + "+" + EMAILS.incrementAndGet() + "@example.com";
    }

    private static String q(String text) {
        return "\"" + text + "\"";
    }

    /** A create body with these JSON values (each already JSON). */
    private static String create(String name, String kyc, String rate, String loginEmail, String password) {
        return "{\"name\":" + name + ",\"category\":\"Groceries\",\"kycVerified\":" + kyc + ",\"commissionRate\":"
                + rate + ",\"loginEmail\":" + loginEmail + ",\"initialPassword\":" + password + "}";
    }

    private static String validCreate() {
        return create(q(NAME), "true", "5.00", q(email()), q(PASSWORD));
    }

    private static Arguments of(Target target, String name, String body) {
        return Arguments.of(name, target, body);
    }

    static Stream<Arguments> cases() {
        String vc = "{\"name\":" + q(NAME) + ",\"category\":\"Groceries\",\"kycVerified\":true,\"commissionRate\":5.00,";
        return Stream.of(
                // POST /merchants (Admin)
                of(Target.CREATE, "loginEmail as a JSON number", create(q(NAME), "true", "5.00", NUMBER, q(PASSWORD))),
                of(Target.CREATE, "loginEmail as an object", create(q(NAME), "true", "5.00", "{\"x\":" + q(email()) + "}", q(PASSWORD))),
                of(Target.CREATE, "initialPassword as a JSON number", create(q(NAME), "true", "5.00", q(email()), NUMBER)),
                of(Target.CREATE, "initialPassword as an object", create(q(NAME), "true", "5.00", q(email()), "{\"x\":" + q(PASSWORD) + "}")),
                of(Target.CREATE, "name as a JSON number", create(NUMBER, "true", "5.00", q(email()), q(PASSWORD))),
                of(Target.CREATE, "name as an object", create("{\"x\":" + q(NAME) + "}", "true", "5.00", q(email()), q(PASSWORD))),
                of(Target.CREATE, "the password as a repeated field name",
                        vc + "\"" + PASSWORD + "\":1,\"" + PASSWORD + "\":2,\"loginEmail\":" + q(email()) + "}"),
                of(Target.CREATE, "the password as an unknown field name",
                        vc + "\"" + PASSWORD + "\":1,\"loginEmail\":" + q(email()) + "}"),
                of(Target.CREATE, "the password as an unknown field's value",
                        vc + "\"note\":" + q(PASSWORD) + ",\"loginEmail\":" + q(email()) + "}"),
                of(Target.CREATE, "the password repeated inside an unknown field",
                        vc + "\"note\":{\"" + PASSWORD + "\":1,\"" + PASSWORD + "\":2},\"loginEmail\":" + q(email()) + "}"),
                of(Target.CREATE, "the password as a trailing token", validCreate() + " " + q(PASSWORD)),
                of(Target.CREATE, "a real body followed by a trailing token", validCreate() + " x"),
                of(Target.CREATE, "an invalid loginEmail (@Valid)",
                        create(q(NAME), "true", "5.00", q(EMAIL_HEAD + "-example.com"), q(PASSWORD))),
                of(Target.CREATE, "a name that is too long (@Valid)",
                        create(q(NAME + "x".repeat(100)), "true", "5.00", q(email()), q(PASSWORD))),
                of(Target.CREATE, "commissionRate as a string", create(q(NAME), "true", q(NUMBER), q(email()), q(PASSWORD))),
                of(Target.CREATE, "commissionRate out of range (@Valid)", create(q(NAME), "true", NUMBER, q(email()), q(PASSWORD))),
                of(Target.CREATE, "kycVerified as a string", create(q(NAME), q(PASSWORD), "5.00", q(email()), q(PASSWORD))),
                of(Target.CREATE, "kycVerified as a number", create(q(NAME), NUMBER, "5.00", q(email()), q(PASSWORD))),
                of(Target.CREATE, "the password unquoted (broken JSON)", create(q(NAME), "true", "5.00", q(email()), PASSWORD)),
                of(Target.CREATE, "the name unquoted (broken JSON)", create("Sentinelname", "true", "5.00", q(email()), q(PASSWORD))),
                of(Target.CREATE, "a password with no punctuation unquoted (broken JSON)",
                        create(q(NAME), "true", "5.00", q(email()), PLAIN_PASSWORD)),
                of(Target.CREATE, "a valid onboarding", validCreate()),
                // POST /merchants/{code}/account-link (Admin, a PENDING merchant)
                of(Target.RETRY, "retry: email as a JSON number", "{\"email\":" + NUMBER + ",\"initialPassword\":" + q(PASSWORD) + "}"),
                of(Target.RETRY, "retry: email as an object", "{\"email\":{\"x\":" + q(email()) + "},\"initialPassword\":" + q(PASSWORD) + "}"),
                of(Target.RETRY, "retry: an invalid email (@Valid)",
                        "{\"email\":" + q(EMAIL_HEAD + "-example.com") + ",\"initialPassword\":" + q(PASSWORD) + "}"),
                of(Target.RETRY, "retry: initialPassword as a JSON number", "{\"email\":" + q(email()) + ",\"initialPassword\":" + NUMBER + "}"),
                of(Target.RETRY, "retry: the password as an unknown field name",
                        "{\"" + PASSWORD + "\":1,\"email\":" + q(email()) + ",\"initialPassword\":\"x\"}"),
                of(Target.RETRY, "retry: a real body followed by a trailing token",
                        "{\"email\":" + q(email()) + ",\"initialPassword\":" + q(PASSWORD) + "} x"),
                of(Target.RETRY, "retry: a valid body", "{\"email\":" + q(email()) + ",\"initialPassword\":" + q(PASSWORD) + "}"),
                // PATCH /merchants/{code} (Admin, a LINKED merchant)
                of(Target.UPDATE, "update: commissionRate as a string", "{\"commissionRate\":" + q(NUMBER) + "}"),
                of(Target.UPDATE, "update: commissionRate out of range (@Valid)", "{\"commissionRate\":" + NUMBER + "}"),
                of(Target.UPDATE, "update: kycVerified as a string", "{\"kycVerified\":" + q(PASSWORD) + "}"),
                of(Target.UPDATE, "update: active as a number", "{\"active\":" + NUMBER + "}"),
                of(Target.UPDATE, "update: the password as an unknown field name", "{\"" + PASSWORD + "\":1,\"active\":true}"),
                of(Target.UPDATE, "update: the password as an unknown field's value", "{\"note\":" + q(PASSWORD) + ",\"active\":true}"),
                of(Target.UPDATE, "update: a real body followed by a trailing token", "{\"active\":true} " + q(PASSWORD)));
    }

    /**
     * Successful answers: Spring logs the object it writes at TRACE ("Writing
     * [...]"), and the call to identity-service at DEBUG. An onboarding, the
     * merchant read, the Admin list, an update and a link retry: their answers
     * hold the name and the login email by design; no log line may hold them,
     * and nothing may hold the password. Mutations: OnboardingResponse's and
     * MerchantResponse's toString with the name; MerchantLinkCall's with the
     * email.
     */
    @org.junit.jupiter.api.Test
    void successfulAnswersPutNoSentinelInALogLine(CapturedOutput output) throws Exception {
        String admin = "Bearer " + TestTokens.admin();
        List<HttpResponse<String>> responses = new ArrayList<>();
        HttpResponse<String> onboarded = send("POST", "/merchants", validCreate(), admin);
        responses.add(onboarded);
        String code = tools.jackson.databind.json.JsonMapper.builder().build().readTree(onboarded.body())
                .get("merchantCode").asString();
        responses.add(send("GET", "/merchants/" + code, null, admin));
        responses.add(send("GET", "/merchants", null, admin));
        responses.add(send("PATCH", "/merchants/" + linkedCode, "{\"commissionRate\":7.50}", admin));
        jdbc.update("UPDATE merchant SET name = ?, login_email = ? WHERE merchant_code = ?", NAME, email(), pendingCode);
        responses.add(send("POST", "/merchants/" + pendingCode + "/account-link",
                "{\"email\":" + q(email()) + ",\"initialPassword\":" + q(PASSWORD) + "}", admin));

        assertThat(responses).extracting(HttpResponse::statusCode).containsExactly(201, 200, 200, 200, 200);
        // The check would be empty if the logging were off.
        assertThat(output).contains("Writing [OnboardingResponse[").contains("Writing [MerchantResponse[")
                .contains("Writing [MerchantPage[").contains("Writing [MerchantLinkCall[");
        List<String> leaks = new ArrayList<>();
        for (String sentinel : SENTINELS) {
            output.getAll().lines().filter(line -> line.contains(sentinel))
                    .forEach(line -> leaks.add("log:" + sentinel + " :: " + line));
        }
        for (HttpResponse<String> response : responses) {
            if (response.body().contains("Zq7Pw")) {
                leaks.add("answer:password :: " + response.body());
            }
        }
        assertThat(leaks).isEmpty();
    }

    private HttpResponse<String> send(String method, String path, String body, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", authorization)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Mutations: the converter bean removed (Spring's own converter: Jackson's
     * message in Spring's DEBUG lines); Jackson's message passed on by the
     * converter; the advice removed (the rejected values of a failed @Valid
     * check at DEBUG); CreateMerchantRequest's toString with the login email;
     * AccountLinkRequest's with the email.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void noSentinelIsInALogLineOrAnAnswer(String name, Target target, String body, CapturedOutput output)
            throws Exception {
        String path = switch (target) {
            case CREATE -> "/merchants";
            case RETRY -> "/merchants/" + pendingCode + "/account-link";
            case UPDATE -> "/merchants/" + linkedCode;
        };
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + TestTokens.admin())
                .header("Content-Type", "application/json")
                .method(target == Target.UPDATE ? "PATCH" : "POST", HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());

        // The check would be empty if the logging were off.
        assertThat(output).contains("Completed " + response.statusCode());
        List<String> leaks = new ArrayList<>();
        boolean success = response.statusCode() < 300;
        for (String sentinel : SENTINELS) {
            // A successful answer holds the merchant's own name and login email by design.
            if (response.body().contains(sentinel) && !(success && OWN_DATA.contains(sentinel))) {
                leaks.add("answer:" + sentinel + " :: " + response.body());
            }
            output.getAll().lines().filter(line -> line.contains(sentinel))
                    .forEach(line -> leaks.add("log:" + sentinel + " :: " + line));
        }
        assertThat(leaks).as("status %d", response.statusCode()).isEmpty();
    }
}

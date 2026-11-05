package com.basketry.merchant.support;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.basketry.merchant.identityclient.InternalApiKey;
import com.basketry.merchant.merchant.Merchant;
import com.basketry.merchant.merchant.MerchantRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared setup for the endpoint tests: one Spring context (cached across the
 * classes that extend this), tokens verified against
 * {@link FakeIdentityServer#shared()}, and merchant-link answers scripted
 * through {@link MockIdentityConfig}.
 */
@SpringBootTest(properties = {
        "basketry.internal.api-key=" + MerchantServiceTestBase.API_KEY,
        "basketry.identity.retry-delay=10ms"})
@AutoConfigureMockMvc
@Import(MockIdentityConfig.class)
public abstract class MerchantServiceTestBase {

    public static final String API_KEY = "test-only-internal-key-not-a-secret-000";

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void identityServer(DynamicPropertyRegistry registry) {
        registry.add("basketry.identity.base-url", () -> FakeIdentityServer.shared().baseUrl());
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected MockRestServiceServer identity;

    @Autowired
    protected MerchantRepository merchants;

    /** The bodies of the merchant-link requests sent in this test, in order. */
    protected final List<String> linkRequests = new ArrayList<>();

    @BeforeEach
    void resetShared() {
        identity.reset();
        merchants.deleteAll();
    }

    /** Every scripted identity-service answer was used, and no other call was made. */
    @AfterEach
    void verifyIdentityCalls() {
        identity.verify();
    }

    protected static <B extends MockHttpServletRequestBuilder> B bearer(B request, String token) {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request;
    }

    protected static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    /** The JSON body of the {@code index}-th merchant-link request, as a map. */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> sentLink(int index) {
        return JSON.readValue(linkRequests.get(index), Map.class);
    }

    /** Expects {@code count} merchant-link calls, each with the API key, and records their bodies. */
    protected ResponseActions expectLink(ExpectedCount count) {
        return identity.expect(count, requestTo(MockIdentityConfig.MERCHANT_LINK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(InternalApiKey.HEADER, API_KEY))
                .andExpect(request -> linkRequests.add(((MockClientHttpRequest) request).getBodyAsString()));
    }

    /** One merchant-link call answered 2xx with this {@code outcome}. */
    protected void identityLinks(HttpStatus status, String outcome) {
        expectLink(ExpectedCount.once()).andRespond(withStatus(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"id\":7,\"buyerActive\":true,\"merchantActive\":true,\"outcome\":\"" + outcome + "\"}"));
    }

    /** One merchant-link call answered with a problem detail, as identity-service sends for 4xx. */
    protected void identityRefuses(HttpStatus status, String problemJson) {
        expectLink(ExpectedCount.once()).andRespond(withStatus(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemJson));
    }

    /** A merchant saved straight to the database, as onboarding leaves it when the link fails. */
    protected Merchant savePending(String loginEmail) {
        return merchants.save(new Merchant(merchants.nextId(), "Corner Shop", "Groceries", true,
                new BigDecimal("5.00"), loginEmail, Instant.now()));
    }

    protected static String conflict(String code) {
        return "{\"type\":\"about:blank\",\"title\":\"Conflict\",\"status\":409,\"detail\":\"refused\",\"code\":\""
                + code + "\"}";
    }
}

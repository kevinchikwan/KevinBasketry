package com.basketry.merchant.identityclient;

import com.basketry.merchant.config.IdentityServiceProperties;
import com.basketry.merchant.identityclient.LinkResult.Failed;
import com.basketry.merchant.identityclient.LinkResult.Linked;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;

/**
 * Calls identity-service's POST /internal/accounts/merchant-link (DESIGN.md
 * Section 3) with the shared API key.
 *
 * <p>The call is idempotent: repeating it for the same email and merchantId
 * answers 200 ALREADY_LINKED. So a connection failure, timeout or 5xx is
 * retried once after {@code basketry.identity.retry-delay}. A 4xx is never
 * retried: it is identity-service's decision and would not change.
 *
 * <p>Never logs the API key or the initial password.
 */
@Component
public class IdentityClient {

    static final String PATH = "/internal/accounts/merchant-link";

    private static final Logger log = LoggerFactory.getLogger(IdentityClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final InternalApiKey apiKey;
    private final Duration retryDelay;

    public IdentityClient(RestClient identityRestClient, InternalApiKey apiKey, IdentityServiceProperties properties) {
        this.restClient = identityRestClient;
        this.apiKey = apiKey;
        this.retryDelay = properties.retryDelay();
    }

    /** Callers check {@link InternalApiKey#isConfigured()} first. */
    public LinkResult link(String email, long merchantId, String initialPassword) {
        MerchantLinkCall call = new MerchantLinkCall(email, merchantId, initialPassword);
        Optional<LinkResult> first = attempt(call);
        if (first.isPresent()) {
            return first.get();
        }
        log.warn("merchant-link for merchantId={} failed transiently; retrying once", merchantId);
        sleep(retryDelay);
        return attempt(call).orElseGet(() -> {
            log.warn("merchant-link for merchantId={} failed transiently twice; merchant stays PENDING", merchantId);
            return Failed.of(LinkErrorCode.IDENTITY_UNAVAILABLE);
        });
    }

    /** Empty means a transient failure (connection, timeout, 5xx) that is worth one retry. */
    private Optional<LinkResult> attempt(MerchantLinkCall call) {
        try {
            return restClient.post()
                    .uri(PATH)
                    .header(InternalApiKey.HEADER, apiKey.value())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(call)
                    .exchange((request, response) -> interpret(response, call));
        } catch (ResourceAccessException e) {
            log.warn("merchant-link for merchantId={} could not reach identity-service: {}",
                    call.merchantId(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<LinkResult> interpret(ConvertibleClientHttpResponse response, MerchantLinkCall call)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.is5xxServerError()) {
            log.warn("merchant-link for merchantId={} got {} from identity-service", call.merchantId(), status.value());
            return Optional.empty();
        }
        Map<String, Object> body = readBody(response);
        if (status.is2xxSuccessful()) {
            return Optional.of(new Linked(outcome(body.get("outcome"))));
        }
        return Optional.of(switch (status.value()) {
            case 409 -> conflict(body, call);
            case 400 -> badRequest(body, call);
            case 401 -> {
                log.error("identity-service refused the internal API key (401). The services' "
                        + "BASKETRY_INTERNAL_API_KEY values must match.");
                yield Failed.of(LinkErrorCode.IDENTITY_AUTH_FAILED);
            }
            default -> {
                log.error("merchant-link for merchantId={} got unexpected status {}", call.merchantId(), status.value());
                yield Failed.of(LinkErrorCode.LINK_REJECTED);
            }
        });
    }

    private static LinkResult conflict(Map<String, Object> body, MerchantLinkCall call) {
        Object code = body.get("code");
        for (LinkErrorCode known : List.of(
                LinkErrorCode.EMAIL_BELONGS_TO_ADMIN,
                LinkErrorCode.ACCOUNT_LINKED_TO_OTHER_MERCHANT,
                LinkErrorCode.BUYER_DEACTIVATED,
                LinkErrorCode.MERCHANT_ALREADY_LINKED_TO_OTHER_ACCOUNT)) {
            if (known.name().equals(code)) {
                return Failed.of(known);
            }
        }
        log.error("merchant-link for merchantId={} got 409 with unknown code {}", call.merchantId(), code);
        return Failed.of(LinkErrorCode.LINK_REJECTED);
    }

    private static LinkResult badRequest(Map<String, Object> body, MerchantLinkCall call) {
        if (body.get("violations") instanceof List<?> list) {
            List<String> violations = list.stream().map(String::valueOf).toList();
            Object detail = body.get("detail");
            return new Failed(LinkErrorCode.WEAK_PASSWORD,
                    detail instanceof String text ? text : LinkErrorCode.WEAK_PASSWORD.adminMessage(),
                    violations);
        }
        if (call.initialPassword() == null) {
            return Failed.of(LinkErrorCode.INITIAL_PASSWORD_REQUIRED);
        }
        log.error("merchant-link for merchantId={} got 400: {}", call.merchantId(), body.get("detail"));
        return Failed.of(LinkErrorCode.LINK_REJECTED);
    }

    /** The problem-detail or success body as a map; empty if there is none or it is not JSON. */
    private static Map<String, Object> readBody(ConvertibleClientHttpResponse response) {
        try {
            Map<String, Object> body = response.bodyTo(JSON_OBJECT);
            return body == null ? Map.of() : body;
        } catch (RestClientException notJson) {
            return Map.of();
        }
    }

    private static AccountOutcome outcome(Object value) {
        for (AccountOutcome outcome : AccountOutcome.values()) {
            if (outcome.name().equals(value)) {
                return outcome;
            }
        }
        return null;
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

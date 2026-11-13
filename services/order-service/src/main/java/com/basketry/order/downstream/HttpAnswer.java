package com.basketry.order.downstream;

import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One GET's status and body, the part every client shares. A timeout or a
 * connection failure becomes {@link DownstreamUnavailableException} with the
 * service's code; the status is the caller's to judge.
 */
record HttpAnswer(int status, String body) {

    private static final Logger log = LoggerFactory.getLogger(HttpAnswer.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static HttpAnswer get(RestClient.RequestHeadersSpec<?> request, String code, String service) {
        try {
            return request.exchange((sent, response) -> new HttpAnswer(response.getStatusCode().value(),
                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (RestClientException unreachable) {
            log.warn("{} did not answer: {}", service, rootMessage(unreachable));
            throw new DownstreamUnavailableException(code, service + " did not answer", unreachable);
        }
    }

    /** The body as a JSON object, or the service's 503 if it is anything else. */
    JsonNode jsonObject(String code, String service) {
        JsonNode tree;
        try {
            tree = JSON.readTree(body);
        } catch (JacksonException unreadable) {
            tree = null;
        }
        if (tree == null || !tree.isObject()) {
            throw unreadable(code, service, "its answer is not a JSON object");
        }
        return tree;
    }

    /** An answer checkout cannot use: logged as an ERROR, because it means a bug or a mismatch, not an outage. */
    static DownstreamUnavailableException unreadable(String code, String service, String why) {
        log.error("{} gave an answer order-service cannot read: {}", service, why);
        return new DownstreamUnavailableException(code, service + " gave an unreadable answer");
    }

    /** 5xx: an outage. 401: the keys differ (an ERROR). Anything else unexpected: a bug (an ERROR). */
    DownstreamUnavailableException unexpected(String code, String service, String keyHint) {
        if (status >= 500) {
            log.warn("{} answered {}", service, status);
        } else if (status == 401) {
            log.error("{} answered 401: {}", service, keyHint);
        } else {
            log.error("{} answered {}, which it never sends for a valid call", service, status);
        }
        return new DownstreamUnavailableException(code, service + " answered " + status);
    }

    static <T> T required(JsonNode node, String field, Function<JsonNode, Boolean> type, Function<JsonNode, T> value,
                          String code, String service) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull() || !type.apply(child)) {
            throw unreadable(code, service, "field " + field + " is missing or has the wrong type");
        }
        return value.apply(child);
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }
}

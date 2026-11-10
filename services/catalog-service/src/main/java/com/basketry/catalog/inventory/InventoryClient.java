package com.basketry.catalog.inventory;

import com.basketry.catalog.config.InternalApiKey;
import com.basketry.catalog.inventory.StockRowAnswer.Kind;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls inventory-service's /internal/stock (DESIGN.md Section 3) with the
 * shared API key. Without a configured key the header is left out, so
 * inventory-service answers 401. Never logs the key.
 */
@Component
public class InventoryClient {

    static final String STOCK_PATH = "/internal/stock";
    /** inventory-service's limit for one stock read. */
    public static final int MAX_IDS_PER_READ = 100;

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final InternalApiKey apiKey;

    public InventoryClient(RestClient inventoryRestClient, InternalApiKey apiKey) {
        this.restClient = inventoryRestClient;
        this.apiKey = apiKey;
    }

    /** POST /internal/stock. Idempotent: repeating it for the same owner answers 200 ALREADY_EXISTS. */
    public StockRowAnswer createStockRow(long productId, long merchantId) {
        try {
            return restClient.post()
                    .uri(STOCK_PATH)
                    .headers(headers -> apiKey.value().ifPresent(key -> headers.set(InternalApiKey.HEADER, key)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("productId", productId, "merchantId", merchantId))
                    .exchange((request, response) -> interpret(response.getStatusCode(), readBody(response)));
        } catch (RestClientException unreachable) {
            return new StockRowAnswer(Kind.RETRY, "inventory-service unreachable: " + unreachable.getMessage());
        }
    }

    /**
     * GET /internal/stock?productIds=. The stock of every product that has a
     * stock row; a product with none is not in the map.
     *
     * @throws InventoryUnavailableException for anything but a readable 200
     */
    public Map<Long, Long> stockOf(Collection<Long> productIds) {
        if (productIds.isEmpty() || productIds.size() > MAX_IDS_PER_READ) {
            throw new IllegalArgumentException("1 to " + MAX_IDS_PER_READ + " product ids, got " + productIds.size());
        }
        String ids = productIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        Map<String, Object> body;
        try {
            body = restClient.get()
                    .uri(uri -> uri.path(STOCK_PATH).queryParam("productIds", ids).build())
                    .headers(headers -> apiKey.value().ifPresent(key -> headers.set(InternalApiKey.HEADER, key)))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() != 200) {
                            throw new InventoryUnavailableException(
                                    "inventory-service answered " + response.getStatusCode().value());
                        }
                        return response.bodyTo(JSON_OBJECT);
                    });
        } catch (RestClientException unreachable) {
            throw new InventoryUnavailableException("inventory-service unreachable: " + unreachable.getMessage(),
                    unreachable);
        }
        return quantities(body);
    }

    private StockRowAnswer interpret(HttpStatusCode status, Map<String, Object> body) {
        int code = status.value();
        if (code == 200 || code == 201) {
            return new StockRowAnswer(Kind.DONE, String.valueOf(body.get("outcome")));
        }
        if (code == 409 && "PRODUCT_OWNED_BY_OTHER_MERCHANT".equals(body.get("code"))) {
            return new StockRowAnswer(Kind.CONFLICT, "409 PRODUCT_OWNED_BY_OTHER_MERCHANT");
        }
        if (code == 401) {
            log.error("inventory-service refused the internal API key (401). The services'"
                    + " BASKETRY_INTERNAL_API_KEY values must match and be set.");
            return new StockRowAnswer(Kind.RETRY, "401 from inventory-service");
        }
        if (status.is4xxClientError()) {
            return new StockRowAnswer(Kind.REJECTED, code + " from inventory-service: " + body.get("detail"));
        }
        return new StockRowAnswer(Kind.RETRY, code + " from inventory-service");
    }

    /** The problem-detail or success body as a map; empty if there is none or it is not JSON. */
    private static Map<String, Object> readBody(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            Map<String, Object> body = response.bodyTo(JSON_OBJECT);
            return body == null ? Map.of() : body;
        } catch (RestClientException notJson) {
            return Map.of();
        }
    }

    private static Map<Long, Long> quantities(Map<String, Object> body) {
        if (body == null || !(body.get("content") instanceof List<?> content)) {
            throw new InventoryUnavailableException("inventory-service's stock answer has no content");
        }
        Map<Long, Long> quantities = new HashMap<>();
        for (Object item : content) {
            if (!(item instanceof Map<?, ?> row && row.get("productId") instanceof Number productId
                    && row.get("quantity") instanceof Number quantity)) {
                throw new InventoryUnavailableException("inventory-service's stock answer has an unreadable item");
            }
            quantities.put(productId.longValue(), quantity.longValue());
        }
        return quantities;
    }
}

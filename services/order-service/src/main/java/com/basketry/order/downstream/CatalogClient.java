package com.basketry.order.downstream;

import com.basketry.order.config.InternalApiKey;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * catalog-service's GET /internal/products, the re-validation of every
 * checkout line (DESIGN.md Section 3, catalog-service), with the internal key.
 * One call for a whole cart: a cart has at most 100 lines, catalog's limit.
 */
@Component
public class CatalogClient {

    static final String SERVICE = "catalog-service";
    public static final int MAX_IDS = 100;
    private static final String CODE = DownstreamUnavailableException.CATALOG;

    private final RestClient restClient;
    private final InternalApiKey apiKey;

    public CatalogClient(RestClient catalogRestClient, InternalApiKey apiKey) {
        this.restClient = catalogRestClient;
        this.apiKey = apiKey;
    }

    /**
     * The products catalog-service knows among {@code productIds}, by id. A
     * product it does not know is not in the map.
     *
     * @throws DownstreamUnavailableException anything but a readable 200
     */
    public Map<Long, CatalogProduct> products(Collection<Long> productIds) {
        if (productIds.isEmpty() || productIds.size() > MAX_IDS) {
            throw new IllegalArgumentException("1 to " + MAX_IDS + " product ids, got " + productIds.size());
        }
        String ids = productIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        HttpAnswer answer = HttpAnswer.get(restClient.get()
                .uri(uri -> uri.path("/internal/products").queryParam("ids", ids).build())
                .headers(headers -> apiKey.value().ifPresent(key -> headers.set(InternalApiKey.HEADER, key))),
                CODE, SERVICE);
        if (answer.status() != 200) {
            throw answer.unexpected(CODE, SERVICE, "the services' BASKETRY_INTERNAL_API_KEY values must match");
        }
        JsonNode content = answer.jsonObject(CODE, SERVICE).get("content");
        if (content == null || !content.isArray()) {
            throw HttpAnswer.unreadable(CODE, SERVICE, "it has no content array");
        }
        Map<Long, CatalogProduct> products = new LinkedHashMap<>();
        for (JsonNode item : content) {
            CatalogProduct product = product(item);
            products.put(product.id(), product);
        }
        return products;
    }

    private static CatalogProduct product(JsonNode item) {
        if (!item.isObject()) {
            throw HttpAnswer.unreadable(CODE, SERVICE, "an item is not an object");
        }
        long id = id(item, "id");
        long merchantId = id(item, "merchantId");
        String name = HttpAnswer.required(item, "name", JsonNode::isString, JsonNode::stringValue, CODE, SERVICE);
        BigDecimal price = HttpAnswer.required(item, "price", JsonNode::isNumber, JsonNode::decimalValue,
                CODE, SERVICE);
        boolean active = HttpAnswer.required(item, "active", JsonNode::isBoolean, JsonNode::booleanValue,
                CODE, SERVICE);
        boolean merchantActive = HttpAnswer.required(item, "merchantActive", JsonNode::isBoolean,
                JsonNode::booleanValue, CODE, SERVICE);
        String stockRow = HttpAnswer.required(item, "stockRow", JsonNode::isString, JsonNode::stringValue,
                CODE, SERVICE);
        if (name.isEmpty() || name.length() > 100) {
            throw HttpAnswer.unreadable(CODE, SERVICE, "a name is empty or longer than 100 characters");
        }
        if (price.signum() <= 0 || price.stripTrailingZeros().scale() > 2) {
            throw HttpAnswer.unreadable(CODE, SERVICE, "a price is not positive with at most 2 decimal places");
        }
        StockRow row;
        try {
            row = StockRow.valueOf(stockRow);
        } catch (IllegalArgumentException unknown) {
            throw HttpAnswer.unreadable(CODE, SERVICE, "a stockRow status is unknown");
        }
        return new CatalogProduct(id, merchantId, name, price.setScale(2), active, merchantActive, row);
    }

    private static long id(JsonNode item, String field) {
        long id = HttpAnswer.required(item, field, node -> node.isIntegralNumber() && node.canConvertToLong(),
                JsonNode::longValue, CODE, SERVICE);
        if (id <= 0) {
            throw HttpAnswer.unreadable(CODE, SERVICE, "field " + field + " is not positive");
        }
        return id;
    }

    /** The four statuses of catalog-service's stock-row request (DESIGN.md Section 2, catalog-service). */
    public enum StockRow {
        PENDING, CREATED, CONFLICT, REJECTED
    }

    /**
     * One product as catalog-service answers it.
     *
     * @param price          the current price, at scale 2
     * @param merchantActive catalog-service's local copy; it lags merchant-service by seconds
     */
    public record CatalogProduct(long id, long merchantId, String name, BigDecimal price, boolean active,
                                 boolean merchantActive, StockRow stockRow) {
    }
}

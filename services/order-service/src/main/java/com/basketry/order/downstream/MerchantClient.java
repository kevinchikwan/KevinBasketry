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
 * merchant-service's GET /internal/merchants: each merchant's live status and
 * the commission rate checkout snapshots (DESIGN.md Section 3,
 * merchant-service), with the internal key. One call for every merchant of a
 * cart; a cart has at most 100 lines, so at most 100 merchants.
 */
@Component
public class MerchantClient {

    static final String SERVICE = "merchant-service";
    private static final String CODE = DownstreamUnavailableException.MERCHANT;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final RestClient restClient;
    private final InternalApiKey apiKey;

    public MerchantClient(RestClient merchantRestClient, InternalApiKey apiKey) {
        this.restClient = merchantRestClient;
        this.apiKey = apiKey;
    }

    /**
     * The merchants merchant-service knows among {@code merchantIds}, by id. A
     * merchant it does not know is not in the map.
     *
     * @throws DownstreamUnavailableException anything but a readable 200
     */
    public Map<Long, MerchantStatus> merchants(Collection<Long> merchantIds) {
        if (merchantIds.isEmpty() || merchantIds.size() > CatalogClient.MAX_IDS) {
            throw new IllegalArgumentException("1 to 100 merchant ids, got " + merchantIds.size());
        }
        String ids = merchantIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        HttpAnswer answer = HttpAnswer.get(restClient.get()
                .uri(uri -> uri.path("/internal/merchants").queryParam("ids", ids).build())
                .headers(headers -> apiKey.value().ifPresent(key -> headers.set(InternalApiKey.HEADER, key))),
                CODE, SERVICE);
        if (answer.status() != 200) {
            throw answer.unexpected(CODE, SERVICE, "the services' BASKETRY_INTERNAL_API_KEY values must match");
        }
        JsonNode content = answer.jsonObject(CODE, SERVICE).get("content");
        if (content == null || !content.isArray()) {
            throw HttpAnswer.unreadable(CODE, SERVICE, "it has no content array");
        }
        Map<Long, MerchantStatus> merchants = new LinkedHashMap<>();
        for (JsonNode item : content) {
            if (!item.isObject()) {
                throw HttpAnswer.unreadable(CODE, SERVICE, "an item is not an object");
            }
            long id = HttpAnswer.required(item, "id", node -> node.isIntegralNumber() && node.canConvertToLong(),
                    JsonNode::longValue, CODE, SERVICE);
            boolean active = HttpAnswer.required(item, "active", JsonNode::isBoolean, JsonNode::booleanValue,
                    CODE, SERVICE);
            BigDecimal rate = HttpAnswer.required(item, "commissionRate", JsonNode::isNumber,
                    JsonNode::decimalValue, CODE, SERVICE);
            if (rate.signum() < 0 || rate.compareTo(HUNDRED) > 0 || rate.stripTrailingZeros().scale() > 2) {
                throw HttpAnswer.unreadable(CODE, SERVICE, "a commission rate is not 0.00 to 100.00");
            }
            merchants.put(id, new MerchantStatus(id, active, rate.setScale(2)));
        }
        return merchants;
    }

    /** @param commissionRate a percentage at scale 2, e.g. 5.00 */
    public record MerchantStatus(long id, boolean active, BigDecimal commissionRate) {
    }
}

package com.basketry.promotion.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.basketry.promotion.support.CouponApiTestBase;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.json.JsonAssert;
import org.springframework.test.json.JsonCompareMode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * docs/contracts/coupon-admin.json: each exchange sent in order, with the
 * caller's token, and its answer, status and body (ROADMAP A7). Numbers are
 * read as written, so 10.00 stays 10.00 and the scale is checked too.
 */
class CouponContractTests extends CouponApiTestBase {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private static final Map<String, String> TOKENS = Map.of("MERCHANT", MERCHANT_TOKEN, "ADMIN", ADMIN_TOKEN,
            "BUYER", BUYER_TOKEN);

    /** services/promotion-service/../../docs/contracts, from where this class was compiled to. */
    static Path file() throws URISyntaxException {
        Path classes = Path.of(CouponContractTests.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        for (Path dir = classes; dir != null; dir = dir.getParent()) {
            if (dir.getFileName() != null && dir.getFileName().toString().equals("promotion-service")
                    && Files.isRegularFile(dir.resolve("build.gradle.kts"))) {
                return dir.resolve("../../docs/contracts/coupon-admin.json").normalize();
            }
        }
        throw new IllegalStateException("promotion-service's directory not found from " + classes);
    }

    /**
     * Mutations: the discount answered without its scale; totalPages one too
     * many; the deactivated coupon answered as still active. Each answer is
     * also pinned elsewhere.
     */
    @Test
    void everyExchangeGetsTheAnswerInTheContract() throws Exception {
        JsonNode contract = JSON.readTree(Files.readString(file(), StandardCharsets.UTF_8));
        clock.set(Instant.parse(contract.path("now").asString()));
        JsonNode exchanges = contract.path("exchanges");
        assertThat(exchanges.size()).as("exchanges in the contract").isGreaterThanOrEqualTo(8);

        String firstId = null;
        for (JsonNode exchange : exchanges) {
            String name = exchange.path("name").asString();
            String path = exchange.path("path").asString();
            if (firstId != null) {
                path = path.replace("{id}", firstId);
            }
            MockHttpServletRequestBuilder sent = request(HttpMethod.valueOf(exchange.path("method").asString()), path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.get(exchange.path("caller").asString()));
            if (exchange.has("request")) {
                sent.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(exchange.get("request")));
            }
            MockHttpServletResponse answer = mvc.perform(sent).andReturn().getResponse();

            assertThat(answer.getStatus()).as(name + ": " + answer.getContentAsString()).isEqualTo(
                    exchange.path("status").asInt());
            ObjectNode body = (ObjectNode) JSON.readTree(answer.getContentAsString());
            if (body.has("id")) {
                assertThat(body.get("id").isIntegralNumber()).as(name).isTrue();
                if (firstId == null) {
                    firstId = body.get("id").asString();
                } else if (path.endsWith("/" + firstId)) {
                    assertThat(body.get("id").asString()).as(name).isEqualTo(firstId);
                }
                body.remove("id");
            }
            // A page: each item's id is a number, left out of the bodies in the file.
            if (body.has("content") && body.get("content").isArray()) {
                for (JsonNode item : body.get("content")) {
                    assertThat(item.get("id").isIntegralNumber()).as(name).isTrue();
                    ((ObjectNode) item).remove("id");
                }
            }
            if (exchange.has("location")) {
                assertThat(answer.getHeader(HttpHeaders.LOCATION)).as(name)
                        .isEqualTo(exchange.path("location").asString().replace("{id}", firstId));
            }
            String expected = JSON.writeValueAsString(exchange.get("body"));
            if (firstId != null) {
                expected = expected.replace("{id}", firstId);
            }
            JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch(expected, JSON.writeValueAsString(body));
            // The scale as written, which a JSON comparison of numbers would not see.
            if (exchange.path("body").has("discountValue")) {
                assertThat(body.get("discountValue").decimalValue().scale()).as(name).isEqualTo(2);
            }
        }
    }
}

package com.basketry.promotion.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * docs/contracts/coupon-pricing.json, the pricing endpoint's request and
 * answers, which order-service's PromotionClient test reads too (ROADMAP A9).
 * Found from this module's directory, not the working directory, as
 * CouponCodeVectorTests finds its file, so Gradle and an IDE read the same one.
 * Numbers are read as written: 1.00 stays 1.00.
 */
public final class PricingContract {

    public static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private PricingContract() {
    }

    /** The whole file; fails, rather than testing nothing, when it is missing or has no request. */
    public static JsonNode read() throws Exception {
        Path file = file();
        JsonNode contract = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        if (!contract.path("request").isObject() || !contract.path("ok").isObject()
                || !contract.path("refusals").isObject()) {
            throw new IllegalStateException(file + " needs a request, an ok answer and the refusals");
        }
        return contract;
    }

    /** services/promotion-service/../../docs/contracts, from where this class was compiled to. */
    static Path file() throws URISyntaxException {
        Path classes = Path.of(PricingContract.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        for (Path dir = classes; dir != null; dir = dir.getParent()) {
            if (dir.getFileName() != null && dir.getFileName().toString().equals("promotion-service")
                    && Files.isRegularFile(dir.resolve("build.gradle.kts"))) {
                return dir.resolve("../../docs/contracts/coupon-pricing.json").normalize();
            }
        }
        throw new IllegalStateException("No promotion-service module directory above " + classes);
    }

    /**
     * The same fields, nothing more or less, and numbers equal with their scale
     * (2.50 is not 2.5). Each difference is listed with its path.
     */
    public static void assertSameJson(JsonNode actual, JsonNode expected) {
        List<String> differences = new ArrayList<>();
        compare("$", actual, expected, differences);
        assertThat(differences).as("differences from the contract").isEmpty();
    }

    private static void compare(String path, JsonNode actual, JsonNode expected, List<String> differences) {
        if (expected.isNumber()) {
            BigDecimal want = expected.decimalValue();
            if (!actual.isNumber() || !actual.decimalValue().equals(want)) {
                differences.add(path + ": expected " + want + ", got " + actual);
            }
        } else if (expected.isObject()) {
            if (!actual.isObject()) {
                differences.add(path + ": expected an object, got " + actual);
                return;
            }
            List<String> names = new ArrayList<>(expected.propertyNames());
            List<String> actualNames = new ArrayList<>(actual.propertyNames());
            if (!new java.util.TreeSet<>(names).equals(new java.util.TreeSet<>(actualNames))) {
                differences.add(path + ": expected fields " + names + ", got " + actualNames);
            }
            for (String name : names) {
                if (actual.has(name)) {
                    compare(path + "." + name, actual.get(name), expected.get(name), differences);
                }
            }
        } else if (expected.isArray()) {
            if (!actual.isArray() || actual.size() != expected.size()) {
                differences.add(path + ": expected " + expected.size() + " elements, got " + actual);
                return;
            }
            for (int i = 0; i < expected.size(); i++) {
                compare(path + "[" + i + "]", actual.get(i), expected.get(i), differences);
            }
        } else if (!expected.equals(actual)) {
            differences.add(path + ": expected " + expected + ", got " + actual);
        }
    }
}

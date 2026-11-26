package com.basketry.promotion.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.promotion.support.CouponApiTestBase;
import com.basketry.promotion.support.TestTokens;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Every answer of POST /coupons and GET /coupons/{id} other than a success
 * (ROADMAP A7), body for body: 400, 401, 403, 404, 405, 409, 413 and 415.
 * The order: 401 (the chain), 403 for a token that is neither Admin's nor a
 * merchant's (no body is read), 400 for the body or the path, 403 for a
 * merchant's scope or merchant id, then 404 (Admin) and 409.
 */
class CouponCreateAnswersTests extends CouponApiTestBase {

    private static final String NOT_JSON = "The body is not JSON of the coupon request's shape.";
    private static final String CODE_RULE = "code: A coupon code must be 4 to 32 characters of A-Z, 0-9, _ and -.";
    private static final String EXPIRY_FORM =
            "expiresAt must be an ISO-8601 instant with Z or an offset, such as 2027-01-31T23:59:59Z.";
    private static final String EXPIRY_RANGE = "expiresAt must be after now and at most 2 years ahead.";

    static String problem(int status, String title, String detail, String instance) {
        return "{\"title\":\"" + title + "\",\"status\":" + status + ",\"detail\":\"" + detail
                + "\",\"instance\":\"" + instance + "\"}";
    }

    static String invalid(String instance, String... violations) {
        String list = Arrays.stream(violations).map(v -> "\"" + v.replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(","));
        return "{\"title\":\"Bad Request\",\"status\":400,\"detail\":\"The coupon request breaks "
                + violations.length + " rule(s).\",\"instance\":\"" + instance + "\",\"violations\":[" + list + "]}";
    }

    static void expect(ResultActions result, int status, String body) throws Exception {
        result.andExpect(status().is(status)).andExpect(content().json(body, JsonCompareMode.STRICT));
    }

    // ---- 401 ----

    /** Mutation: the coupon chain permits every request. */
    @Test
    void noTokenIs401() throws Exception {
        mvc.perform(post("/coupons").contentType(MediaType.APPLICATION_JSON).content(merchantBody("SAVE10")))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, org.hamcrest.Matchers.startsWith("Bearer")));
        mvc.perform(get("/coupons/1"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    }

    /**
     * An expired token, a token signed with a key identity-service does not
     * publish, and no JWT at all. Mutation: the decoder accepts an expired token.
     */
    @Test
    void aTokenThatCannotBeAcceptedIs401() throws Exception {
        for (String token : List.of(TestTokens.expiredMerchant(21, MERCHANT),
                TestTokens.sign(TestTokens.newKey(), 1, List.of("ADMIN"), null, Instant.now().plusSeconds(3600)),
                "not-a-jwt")) {
            create(token, merchantBody("SAVE10")).andExpect(status().isUnauthorized())
                    .andExpect(content().string(""));
            read(token, "1").andExpect(status().isUnauthorized());
        }
        assertThat(coupons.count()).isZero();
    }

    // ---- 403 before the body ----

    /**
     * A buyer, a MERCHANT token without a merchant id, and one with a
     * merchant id that is not a number above 0: 403 whatever the body, which
     * is not read (broken JSON is still a 403). Mutation: the capability
     * check after the body is read.
     */
    @ParameterizedTest
    @MethodSource("notAdminOrMerchant")
    void aTokenThatIsNeitherAdminsNorAMerchantsIs403(String token) throws Exception {
        String forbidden = problem(403, "Forbidden", "Only Admin and merchants can manage coupons.", "/coupons");
        expect(create(token, merchantBody("SAVE10")), 403, forbidden);
        expect(create(token, "{\"code\":"), 403, forbidden);
        expect(read(token, "1"), 403,
                problem(403, "Forbidden", "Only Admin and merchants can manage coupons.", "/coupons/1"));
        assertThat(coupons.count()).isZero();
    }

    static Stream<String> notAdminOrMerchant() {
        Instant hour = Instant.now().plusSeconds(3600);
        return Stream.of(BUYER_TOKEN,
                TestTokens.sign(TestTokens.KEY, 24, List.of("BUYER", "MERCHANT"), null, hour),
                TestTokens.sign(TestTokens.KEY, 25, List.of("BUYER", "MERCHANT"), 0L, hour),
                TestTokens.sign(TestTokens.KEY, 26, List.of("BUYER"), MERCHANT, hour));
    }

    // ---- 400: the body ----

    /** No body, a blank one, an array, broken JSON: the fixed detail, nothing echoed. Mutation: an empty body handed to Jackson. */
    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "[]", "{\"code\":", "{\"code\":\"SAVE10\"", "\"SAVE10\"", "SAVE10"})
    void aBodyThatIsNotJsonOfTheShapeIs400(String body) throws Exception {
        expect(create(ADMIN_TOKEN, body), 400, problem(400, "Bad Request", NOT_JSON, "/coupons"));
    }

    /** Mutation: the JSON literal null handed on as a request. */
    @Test
    void theJsonLiteralNullIs400() throws Exception {
        expect(create(ADMIN_TOKEN, "null"), 400, invalid("/coupons", "The body must be a JSON object."));
    }

    /**
     * Each field takes only its own JSON type; the path names the field, never
     * the value. Mutations: a strict string reader removed (code, scope,
     * discountType, expiresAt each).
     */
    @ParameterizedTest
    @MethodSource("wrongTypes")
    void aValueOfTheWrongJsonTypeIs400AtItsField(String field, String body) throws Exception {
        expect(create(ADMIN_TOKEN, body), 400, problem(400, "Bad Request", NOT_JSON + " At: " + field + ".",
                "/coupons"));
    }

    static Stream<Arguments> wrongTypes() {
        return Stream.of(
                Arguments.of("code", "{\"code\":5}"),
                Arguments.of("code", "{\"code\":true}"),
                Arguments.of("scope", "{\"scope\":1}"),
                Arguments.of("discountType", "{\"discountType\":false}"),
                Arguments.of("expiresAt", "{\"expiresAt\":1798761600}"),
                Arguments.of("merchantId", "{\"merchantId\":\"3\"}"),
                Arguments.of("merchantId", "{\"merchantId\":1.5}"),
                Arguments.of("discountValue", "{\"discountValue\":\"10.00\"}"),
                Arguments.of("discountValue", "{\"discountValue\":true}"));
    }

    /**
     * A repeated field and anything after the body (StrictJsonConfig).
     * Mutation: the controller reads with a JsonMapper of its own.
     */
    @ParameterizedTest
    @ValueSource(strings = {"{\"code\":\"SAVE10\",\"code\":\"SAVE20\"}", "{\"code\":\"SAVE10\"} x",
            "{\"code\":\"SAVE10\"} {}"})
    void aRepeatedFieldOrATrailingTokenIs400(String body) throws Exception {
        create(ADMIN_TOKEN, body).andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(NOT_JSON)))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SAVE"))));
    }

    /** Every required field missing, named at once, in this order. Mutation: the other rules skipped after a broken code. */
    @Test
    void anEmptyObjectNamesEveryMissingField() throws Exception {
        expect(create(ADMIN_TOKEN, "{}"), 400, invalid("/coupons", "code is required.", "scope is required.",
                "discountType is required.", "discountValue is required.", "expiresAt is required."));
    }

    /** Mutation: a refused code not named, left to the coupon's own check (a 500). */
    @ParameterizedTest
    @ValueSource(strings = {"ABC", "A23456789012345678901234567890123", "SAVE 10", "SAVE.10", "\\u017fAVE10"})
    void aCodeTheRuleRefusesIs400(String code) throws Exception {
        expect(create(ADMIN_TOKEN, platformBody(code)), 400, invalid("/coupons", CODE_RULE));
    }

    /** The scope and the discount type are exact names. Mutation: the scope read without regard to case. */
    @Test
    void anUnknownScopeOrDiscountTypeIs400() throws Exception {
        expect(create(ADMIN_TOKEN, platformBody("SAVE10").replace("PLATFORM", "GLOBAL")), 400,
                invalid("/coupons", "scope must be MERCHANT or PLATFORM."));
        expect(create(ADMIN_TOKEN, platformBody("SAVE10").replace("PLATFORM", "platform")), 400,
                invalid("/coupons", "scope must be MERCHANT or PLATFORM."));
        expect(create(ADMIN_TOKEN, platformBody("SAVE10").replace("FIXED", "PERCENT")), 400,
                invalid("/coupons", "discountType must be PERCENTAGE or FIXED."));
    }

    /** Mutation: merchantId 0 accepted. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void aMerchantIdThatIsNotAboveZeroIs400(String merchantId) throws Exception {
        expect(create(ADMIN_TOKEN, merchantBody("SAVE10").replace("{", "{\"merchantId\":" + merchantId + ",")), 400,
                invalid("/coupons", "merchantId must be a whole number above 0."));
    }

    /** Mutation: a platform coupon keeps the merchant id it was sent. */
    @Test
    void aPlatformCouponWithAMerchantIdIs400() throws Exception {
        expect(create(ADMIN_TOKEN, platformBody("SAVE10").replace("{", "{\"merchantId\":3,")), 400,
                invalid("/coupons", "merchantId is only for a MERCHANT coupon."));
    }

    /** Mutation: Admin's merchant coupon without a merchant id let through (a 500). */
    @Test
    void adminsMerchantCouponNeedsAMerchantId() throws Exception {
        expect(create(ADMIN_TOKEN, merchantBody("SAVE10")), 400,
                invalid("/coupons", "merchantId is required when Admin creates a MERCHANT coupon."));
    }

    /**
     * The pricing rules for a discount: percentage 0.01 to 100.00, fixed above
     * 0 and at most the maximum, at most 2 decimals. Mutation: a discount
     * outside them not named, left to the coupon (a 500).
     */
    @ParameterizedTest
    @MethodSource("badDiscounts")
    void aDiscountOutsideThePricingRulesIs400(String type, String value, String violation) throws Exception {
        String body = "{\"code\":\"SAVE10\",\"scope\":\"PLATFORM\",\"discountType\":\"" + type
                + "\",\"discountValue\":" + value + ",\"expiresAt\":\"2027-06-01T00:00:00Z\"}";
        expect(create(ADMIN_TOKEN, body), 400, invalid("/coupons", violation));
    }

    static Stream<Arguments> badDiscounts() {
        String percentage = "discountValue: A percentage discount is 0.01 to 100.00.";
        String fixed = "discountValue: A fixed discount is above 0 and at most 9999999999.99.";
        String decimals = "discountValue: A discount has at most 2 decimal places.";
        return Stream.of(
                Arguments.of("PERCENTAGE", "0", percentage),
                Arguments.of("PERCENTAGE", "-5", percentage),
                Arguments.of("PERCENTAGE", "100.01", percentage),
                Arguments.of("PERCENTAGE", "10.005", decimals),
                Arguments.of("FIXED", "0", fixed),
                Arguments.of("FIXED", "-0.01", fixed),
                Arguments.of("FIXED", "10000000000.00", fixed),
                Arguments.of("FIXED", "0.001", decimals));
    }

    /** Only an instant with Z or an offset. Mutation: an expiry without an offset read as UTC. */
    @ParameterizedTest
    @ValueSource(strings = {"2027-06-01", "2027-06-01T00:00:00", "tomorrow", "", "2027-06-01T00:00:00Z[UTC]"})
    void anExpiryThatIsNotAnInstantIs400(String expiresAt) throws Exception {
        expect(create(ADMIN_TOKEN, platformBody("SAVE10").replace("2027-06-01T00:00:00Z", expiresAt)), 400,
                invalid("/coupons", EXPIRY_FORM));
    }

    /**
     * After now (the end is exclusive: a coupon expiring now is already
     * expired) and at most 2 years ahead. Mutations: "after now" as "not before
     * now"; 5 years instead of 2.
     */
    @ParameterizedTest
    @ValueSource(strings = {"2027-01-01T00:00:00Z", "2026-12-31T23:59:59Z", "2027-01-01T01:00:00+01:00",
            "2029-01-01T00:00:00.000001Z", "2031-12-31T00:00:00Z"})
    void anExpiryNotAfterNowOrMoreThanTwoYearsAheadIs400(String expiresAt) throws Exception {
        expect(create(ADMIN_TOKEN, platformBody("SAVE10").replace("2027-06-01T00:00:00Z", expiresAt)), 400,
                invalid("/coupons", EXPIRY_RANGE));
    }

    /**
     * Unknown fields: a safe name is shown, any other only counted
     * (order-service's rule). Mutations: every name shown; the digit-run
     * check removed.
     */
    @Test
    void anUnknownFieldIsNamedOnlyWhenItsNameIsSafe() throws Exception {
        expect(create(ADMIN_TOKEN, platformBody("SAVE10").replace("{", "{\"note\":1,\"active\":false,")), 400,
                invalid("/coupons", "Unknown field(s): active, note."));
        // A run of 5 digits is shown, of 6 not; 41 characters are shown, 42 not.
        expect(create(ADMIN_TOKEN, platformBody("SAVE10").replace("{", "{\"4929117300568841\":1,\"a492911\":1,"
                        + "\"a49291\":1,\"Zq7Pw!Lk9xQ\":1,\"" + "a".repeat(42) + "\":1,\"" + "b".repeat(41) + "\":1,")),
                400, invalid("/coupons", "Unknown field(s): a49291, " + "b".repeat(41) + ".",
                        "4 unknown field(s) at the top level, with a name that is not shown."));
    }

    /** Every broken rule at once, in the order of the fields. Mutation: the other rules skipped after a broken code. */
    @Test
    void everyBrokenRuleIsNamedAtOnce() throws Exception {
        String body = "{\"code\":\"AB\",\"scope\":\"PLATFORM\",\"merchantId\":3,\"discountType\":\"PERCENTAGE\","
                + "\"discountValue\":0,\"expiresAt\":\"2020-01-01T00:00:00Z\",\"extra\":1}";
        expect(create(ADMIN_TOKEN, body), 400, invalid("/coupons", "Unknown field(s): extra.", CODE_RULE,
                "merchantId is only for a MERCHANT coupon.",
                "discountValue: A percentage discount is 0.01 to 100.00.", EXPIRY_RANGE));
    }

    // ---- 403 after the body ----

    /**
     * A merchant creates only its own MERCHANT coupons. Mutations: a merchant
     * allowed a platform coupon; the body's merchant id taken over the token's.
     */
    @Test
    void aMerchantCannotCreateAPlatformCouponOrAnotherMerchantsCoupon() throws Exception {
        String forbidden = problem(403, "Forbidden", "A merchant can create only its own MERCHANT coupons.",
                "/coupons");
        expect(create(MERCHANT_TOKEN, platformBody("SAVE10")), 403, forbidden);
        expect(create(MERCHANT_TOKEN, merchantBody("SAVE10").replace("{", "{\"merchantId\":" + OTHER_MERCHANT + ",")),
                403, forbidden);
        assertThat(coupons.count()).isZero();
    }

    /** A body rule is a 400 before the merchant's 403. Mutation: the merchant's scope checked before the body rules. */
    @Test
    void aBodyRuleComesBeforeTheMerchantsScope() throws Exception {
        expect(create(MERCHANT_TOKEN, platformBody("AB")), 400, invalid("/coupons", CODE_RULE));
    }

    // ---- 409 ----

    /**
     * The code is unique after normalising, across both scopes (one
     * namespace); the answer does not echo it. Mutation: the unique
     * constraint's violation not turned into the 409 (a 500).
     */
    @Test
    void aCodeTakenAfterNormalisingIs409() throws Exception {
        savePlatformCoupon("SAVE10", Instant.parse("2027-06-01T00:00:00Z"));
        String taken = "{\"title\":\"Conflict\",\"status\":409,\"detail\":\"The coupon code is taken.\","
                + "\"instance\":\"/coupons\",\"code\":\"COUPON_CODE_TAKEN\"}";

        expect(create(MERCHANT_TOKEN, merchantBody(" save10 ")), 409, taken);
        expect(create(ADMIN_TOKEN, platformBody("Save10")), 409, taken);
        assertThat(coupons.count()).isOne();
    }

    // ---- 413, 415, 405 ----

    /** Before the chain, as pricing: even without a token. Mutation: the body limit not on /coupons. */
    @Test
    void aBodyOver64KbIs413() throws Exception {
        String large = platformBody("SAVE10").replace("}", ",\"pad\":\"" + "x".repeat(64 * 1024) + "\"}");
        String tooLarge = "{\"type\":\"about:blank\",\"title\":\"Content Too Large\",\"status\":413,"
                + "\"detail\":\"The body is larger than 64 KB.\",\"code\":\"BODY_TOO_LARGE\"}";
        expect(create(ADMIN_TOKEN, large), 413, tooLarge);
        expect(mvc.perform(post("/coupons").contentType(MediaType.APPLICATION_JSON).content(large)), 413, tooLarge);
    }

    /** Spring's own answers ('null' when no Content-Type is sent). Mutation: any content type read as JSON. */
    @Test
    void aBodyThatIsNotJsonIs415() throws Exception {
        expect(mvc.perform(post("/coupons").header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.TEXT_PLAIN).content(platformBody("SAVE10"))), 415,
                problem(415, "Unsupported Media Type", "Content-Type 'text/plain' is not supported.", "/coupons"));
        expect(mvc.perform(post("/coupons").header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                        .content(platformBody("SAVE10"))), 415,
                problem(415, "Unsupported Media Type", "Content-Type 'null' is not supported.",
                        "/coupons"));
    }

    /** Spring's own answer, with Allow. Mutation: GET /coupons/{id} answering any method. */
    @Test
    void anotherMethodIs405() throws Exception {
        expect(mvc.perform(put("/coupons").header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)), 405,
                problem(405, "Method Not Allowed", "Method 'PUT' is not supported.", "/coupons"));
        expect(mvc.perform(delete("/coupons/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)), 405,
                problem(405, "Method Not Allowed", "Method 'DELETE' is not supported.", "/coupons/1"));
        mvc.perform(delete("/coupons/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
                .andExpect(header().string(HttpHeaders.ALLOW, "GET"));
    }

    // ---- GET /coupons/{id} ----

    /** Mutation: the id rule removed (Long.parseLong on anything: a 500). */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1", "1.5", "99999999999999999999", "1a"})
    void anIdThatIsNotAWholeNumberAboveZeroIs400(String id) throws Exception {
        read(ADMIN_TOKEN, id).andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"violations\":[\"id must be a whole number above 0.\"]")));
    }

    /** Admin alone learns that an id is missing. Mutation: Admin's 404 as a 403. */
    @Test
    void aMissingCouponIs404ForAdmin() throws Exception {
        expect(read(ADMIN_TOKEN, "999"), 404, problem(404, "Not Found", "No coupon has this id.", "/coupons/999"));
    }

    /**
     * A merchant gets 403 for another merchant's coupon, a platform coupon and
     * a missing one alike. Mutations: a merchant may read any coupon; a
     * missing coupon is a 404 for a merchant.
     */
    @Test
    void aMerchantGets403ForACouponThatIsNotItsOwn() throws Exception {
        long others = saveMerchantCoupon("OTHER10", OTHER_MERCHANT, Instant.parse("2027-06-01T00:00:00Z")).getId();
        long platform = savePlatformCoupon("PLAT10", Instant.parse("2027-06-01T00:00:00Z")).getId();
        for (long id : List.of(others, platform, 999_999L)) {
            expect(read(MERCHANT_TOKEN, Long.toString(id)), 403, problem(403, "Forbidden",
                    "Only Admin and the coupon's merchant can see this coupon.", "/coupons/" + id));
        }
    }
}

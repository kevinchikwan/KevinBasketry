package com.basketry.catalog.merchantstatus;

import static com.basketry.catalog.merchantstatus.StatusEvents.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The parser alone, no Spring: every rule is checked by hand on the tree, so
 * the mapper's own settings do not matter here. Every refusal names its rule
 * and never quotes the value.
 */
class MerchantStatusParserTests {

    private final MerchantStatusParser parser = new MerchantStatusParser(JsonMapper.builder().build());

    private static String withField(String name, String json) {
        return event(42, false, 3).replaceFirst("\"" + name + "\":[^,}]+", "\"" + name + "\":" + json);
    }

    private static String without(String name) {
        return event(42, false, 3).replaceFirst("\"" + name + "\":[^,}]+,?", "");
    }

    private void assertRefused(String value, String message) {
        assertThatThrownBy(() -> parser.parse(value))
                .isInstanceOf(InvalidMerchantStatusEventException.class)
                .hasMessage(message)
                .hasNoCause();
    }

    /** The DESIGN.md example; merchantCode, eventId and occurredAt are not used. */
    @Test
    void aValidEventIsRead() {
        assertThat(parser.parse(event(42, false, 3))).isEqualTo(new MerchantStatusEvent(42, false, 3));
        assertThat(parser.parse(event(42, true, 4))).isEqualTo(new MerchantStatusEvent(42, true, 4));
    }

    /** As identity-service. Mutation: refuse fields outside the four required ones. */
    @Test
    void unknownFieldsAreIgnored() {
        assertThat(parser.parse("{\"schemaVersion\":1,\"merchantId\":42,\"active\":true,\"version\":2,"
                + "\"region\":\"EU\",\"extra\":{\"a\":[1,2]}}")).isEqualTo(new MerchantStatusEvent(42, true, 2));
    }

    /** Mutation: drop STRICT_DUPLICATE_DETECTION (the last "active" would win and undo the deactivation). */
    @Test
    void aRepeatedFieldIsRefused() {
        assertRefused("{\"schemaVersion\":1,\"merchantId\":42,\"active\":false,\"active\":true,\"version\":3}",
                "The value is not valid JSON (line 1, column 59)");
    }

    /**
     * Jackson 3 refuses trailing tokens by default; the parser says so
     * explicitly, so a change of default cannot turn it off. Mutation:
     * .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).
     */
    @Test
    void anythingAfterTheObjectIsRefused() {
        assertRefused(event(42, false, 3) + " {\"active\":true}", "The value is not valid JSON (line 1, column 178)");
    }

    /**
     * Checked first: another schema version may not have the fields below, so
     * its refusal must name the version, not a field. Mutation: check
     * schemaVersion after the fields (these would be refused for merchantId).
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"schemaVersion\":2,\"merchant\":42}",
            "{\"schemaVersion\":\"1\",\"merchantId\":42,\"active\":true,\"version\":1}",
            "{\"schemaVersion\":1.0,\"merchantId\":42,\"active\":true,\"version\":1}",
            "{\"merchantId\":42,\"active\":true,\"version\":1}"})
    void schemaVersionIsCheckedFirstAndMustBeOne(String value) {
        assertRefused(value, "schemaVersion is missing or not 1");
    }

    /** No conversion between types. Mutation: accept a textual or numeric active ("asBoolean"). */
    @ParameterizedTest
    @ValueSource(strings = {"\"false\"", "0", "1", "\"true\""})
    void activeMustBeAJsonBoolean(String active) {
        assertRefused(withField("active", active), "active must be a JSON boolean");
    }

    /** Mutation: accept any number that converts to a long (7.0 and 1e3 would be read as ids). */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "merchantId|42.0", "merchantId|\"42\"", "merchantId|1e3", "merchantId|9223372036854775808",
            "version|3.0", "version|\"3\"", "version|18446744073709551616"})
    void idsAndVersionsMustBeWholeJsonNumbersThatFit64Bits(String field, String value) {
        assertRefused(withField(field, value), field + " must be a whole JSON number that fits 64 bits");
    }

    /** The largest long is a valid id and version. */
    @Test
    void theLargestLongIsAccepted() {
        assertThat(parser.parse(event(Long.MAX_VALUE, true, Long.MAX_VALUE)))
                .isEqualTo(new MerchantStatusEvent(Long.MAX_VALUE, true, Long.MAX_VALUE));
    }

    /**
     * Refused here, with a message, not later by MerchantStatusService's
     * IllegalArgumentException (which the dead letter would carry as an
     * unexpected error). Mutation: drop the "must be positive" check.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"merchantId|0", "merchantId|-1", "version|0", "version|-5"})
    void idsAndVersionsMustBePositive(String field, String value) {
        assertRefused(withField(field, value), field + " must be positive");
    }

    @ParameterizedTest
    @ValueSource(strings = {"merchantId", "active", "version"})
    void eachUsedFieldIsRequired(String field) {
        assertRefused(without(field), field + " is required");
        assertRefused(withField(field, "null"), field + " is required");
    }

    @Test
    void valuesThatAreNotAJsonObjectAreRefused() {
        assertRefused(null, "The record has no value");
        assertRefused("not json", "The value is not valid JSON (line 1, column 1)");
        assertRefused("[1,2]", "The value is not a JSON object");
        assertRefused("42", "The value is not a JSON object");
        assertRefused("", "The value is not a JSON object");
    }

    /** No message quotes the value, whatever it holds. */
    @Test
    void noRefusalQuotesTheValue() {
        String secretish = "SENTINEL-7f3a";
        for (String value : new String[] {
                "{\"schemaVersion\":1,\"merchantId\":\"" + secretish + "\"}",
                "{\"" + secretish + "\":1,",
                withField("active", "\"" + secretish + "\""),
                "{\"schemaVersion\":\"" + secretish + "\"}"}) {
            assertThatThrownBy(() -> parser.parse(value))
                    .isInstanceOf(InvalidMerchantStatusEventException.class)
                    .satisfies(refusal -> assertThat(refusal.getMessage()).doesNotContain(secretish));
        }
    }
}

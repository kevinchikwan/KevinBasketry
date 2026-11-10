package com.basketry.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Row 2 of the Day 12 review: the field rules of a product, on create and on update. */
class ProductCommandTests {

    private static CreateProductCommand create(String name, String description, String category, String price) {
        return new CreateProductCommand(3, name, description, category, price == null ? null : new BigDecimal(price));
    }

    private static InvalidProductException refused(Runnable build) {
        try {
            build.run();
        } catch (InvalidProductException refused) {
            return refused;
        }
        throw new AssertionError("expected InvalidProductException");
    }

    @Test
    void textIsTrimmedAndAMissingDescriptionIsEmpty() {
        CreateProductCommand command = create("  Tea  ", null, " Drinks ", "2.50");

        assertThat(command.name()).isEqualTo("Tea");
        assertThat(command.category()).isEqualTo("Drinks");
        assertThat(command.description()).isEmpty();
    }

    /** Mutation: an off-by-one in a length check (> max + 1, or >= max). */
    @Test
    void eachLengthLimitIsInclusive() {
        create("n".repeat(100), "d".repeat(2000), "c".repeat(50), "1.00");

        assertThat(refused(() -> create("n".repeat(101), "", "c", "1.00")).getViolations())
                .containsExactly("name must be at most 100 characters.");
        assertThat(refused(() -> create("n", "d".repeat(2001), "c", "1.00")).getViolations())
                .containsExactly("description must be at most 2000 characters.");
        assertThat(refused(() -> create("n", "", "c".repeat(51), "1.00")).getViolations())
                .containsExactly("category must be at most 50 characters.");
    }

    /** Mutation: checking the length before trimming (100 letters plus spaces would be refused). */
    @Test
    void theLimitAppliesAfterTrimming() {
        assertThat(create("  " + "n".repeat(100) + "  ", "", "c", "1.00").name()).hasSize(100);
    }

    @Test
    void blankNameAndCategoryAreRequired() {
        assertThat(refused(() -> create("   ", "", " ", "1.00")).getViolations())
                .containsExactly("name is required.", "category is required.");
    }

    /** Mutation: stop at the first violation (the merchant would fix them one request at a time). */
    @Test
    void everyViolationIsNamedAtOnce() {
        InvalidProductException refused = refused(() -> new CreateProductCommand(0, "", "", "", null));

        assertThat(refused.getViolations()).containsExactly("merchantId must be positive.", "name is required.",
                "category is required.", "price is required.");
    }

    @Test
    void thePriceRangeIsInclusive() {
        assertThat(create("n", "", "c", "0.01").price()).isEqualByComparingTo("0.01");
        assertThat(create("n", "", "c", "99999.99").price()).isEqualByComparingTo("99999.99");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1.00", "100000.00", "99999.991"})
    void aPriceOutsideTheRangeOrWithMoreDecimalsIsRefused(String price) {
        assertThatThrownBy(() -> create("n", "", "c", price)).isInstanceOf(InvalidProductException.class);
    }

    /** Mutation: rounding to 2 decimals instead of refusing (9.999 would become 10.00). */
    @Test
    void moreThanTwoDecimalsIsRefusedNeverRounded() {
        assertThat(refused(() -> create("n", "", "c", "9.999")).getViolations())
                .containsExactly("price must have at most 2 decimal places.");
    }

    /** Mutation: comparing the raw scale (9.990 would be refused, unlike merchant-service's @Digits). */
    @Test
    void trailingZerosDoNotCountAndThePriceIsStoredAtScaleTwo() {
        assertThat(create("n", "", "c", "9.990").price()).isEqualTo(new BigDecimal("9.99"));
        assertThat(create("n", "", "c", "1E+2").price()).isEqualTo(new BigDecimal("100.00"));
        assertThat(create("n", "", "c", "7").price()).isEqualTo(new BigDecimal("7.00"));
    }

    /** Mutation: accept an empty change (a PATCH {} would be a silent 200). */
    @Test
    void aChangeNeedsAtLeastOneField() {
        assertThat(refused(() -> new ProductChanges(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty())).getViolations())
                .containsExactly("Give at least one of name, description, category, price and active.");
    }

    @Test
    void aChangeFollowsTheSameRulesAndABlankDescriptionClearsIt() {
        ProductChanges changes = new ProductChanges(Optional.of(" Tea "), Optional.of("  "), Optional.empty(),
                Optional.of(new BigDecimal("3.5")), Optional.empty());

        assertThat(changes.name()).hasValue("Tea");
        assertThat(changes.description()).hasValue("");
        assertThat(changes.price()).hasValue(new BigDecimal("3.50"));
        assertThat(refused(() -> new ProductChanges(Optional.of(" "), Optional.empty(), Optional.empty(),
                Optional.of(new BigDecimal("0")), Optional.empty())).getViolations())
                .containsExactly("name is required.", "price must be between 0.01 and 99999.99.");
    }
}

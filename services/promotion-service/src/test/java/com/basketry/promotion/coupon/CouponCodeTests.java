package com.basketry.promotion.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The code rule (DESIGN.md Section 2, promotion-service, Codes). The shared cases are in CouponCodeVectorTests. */
class CouponCodeTests {

    /** Mutation: {@code raw.strip()} dropped (the spaces are then refused characters). */
    @Test
    void whatAPersonTypedIsStrippedAndUpperCased() {
        assertThat(CouponCode.normalise("  save-10_x ").value()).isEqualTo("SAVE-10_X");
    }

    /**
     * Pricing normalises again the code order-service already normalised.
     * Mutation: normalise also removes separators ({@code .replace("-", "")}).
     */
    @Test
    void aNormalisedCodeGoesThroughUnchanged() {
        for (String code : new String[] {"SAVE10", "SAVE-10", "A_B-C", "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"}) {
            assertThat(CouponCode.normalise(CouponCode.normalise(code).value()).value()).isEqualTo(code);
        }
    }

    /** Mutation: {@code toUpperCase()} without Locale.ROOT (Turkish makes "DİSCOUNT"). */
    @Test
    void upperCasingDoesNotDependOnTheDefaultLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(CouponCode.normalise("discount").value()).isEqualTo("DISCOUNT");
        } finally {
            Locale.setDefault(before);
        }
    }

    /** Mutation: upper-case first, then check only the upper-cased code (ſ, ß, ﬁ and ı then pass). */
    @Test
    void charactersThatUpperCaseIntoAsciiAreRefused() {
        for (String typed : new String[] {"\u017Fave10", "stra\u00DFe", "\uFB01ve10", "d\u0131scount"}) {
            assertThatThrownBy(() -> CouponCode.normalise(typed)).isInstanceOf(InvalidCouponException.class);
        }
    }

    /** Mutations: the pattern's upper bound dropped ({@code {4,}}); {@code MIN_LENGTH = 3}. */
    @Test
    void aCodeIsFourToThirtyTwoCharacters() {
        assertThat(CouponCode.normalise("ABCD").value()).isEqualTo("ABCD");
        assertThat(CouponCode.normalise("ABCDEFGHIJKLMNOPQRSTUVWXYZ012345").value()).hasSize(32);
        assertThatThrownBy(() -> CouponCode.normalise("ABC")).isInstanceOf(InvalidCouponException.class);
        assertThatThrownBy(() -> CouponCode.normalise("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456"))
                .isInstanceOf(InvalidCouponException.class);
    }

    /** Mutation: the compact constructor's check removed. */
    @Test
    void theConstructorTakesOnlyANormalisedCode() {
        for (String value : new String[] {"save10", " SAVE10", "SAVE 10", null}) {
            assertThatThrownBy(() -> new CouponCode(value)).isInstanceOf(InvalidCouponException.class);
        }
    }
}

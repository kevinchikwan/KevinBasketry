package com.basketry.merchant.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** order-service's rule for the unknown field names a 400 may name, at its edges. */
class UnknownFieldNamesTests {

    /** Mutation: the name rule widened to 42 characters. */
    @ParameterizedTest
    @ValueSource(strings = {"a", "note", "active", "Z_9", "a12345", "abcdefghijabcdefghijabcdefghijabcdefghija"})
    void aNameThatMayBeShownIsShown(String name) {
        assertThat(UnknownFieldNames.shown(name)).isTrue();
        assertThat(UnknownFieldNames.shownOf(List.of(name))).containsExactly(name);
        assertThat(UnknownFieldNames.hiddenOf(List.of(name))).isZero();
    }

    /**
     * Not a letter first, a character outside letters, digits and _, more
     * than 41 characters, or 6 digits in a row. Mutations: the name rule
     * widened to 42 characters; the digit-run check removed.
     */
    @ParameterizedTest
    @ValueSource(strings = {"1note", "_note", "Zq7Pw!Lk9xQ", "e-mail", "a b",
            "abcdefghijabcdefghijabcdefghijabcdefghijab", "a123456", "4929117300568841"})
    void anyOtherNameIsOnlyCounted(String name) {
        assertThat(UnknownFieldNames.shown(name)).isFalse();
        assertThat(UnknownFieldNames.shownOf(List.of("note", name))).containsExactly("note");
        assertThat(UnknownFieldNames.hiddenOf(List.of("note", name))).isEqualTo(1);
    }
}

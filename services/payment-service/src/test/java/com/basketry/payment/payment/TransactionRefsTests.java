package com.basketry.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TransactionRefsTests {

    @Test
    void aNewRefIsTxnAnd32LowercaseHexDigits() {
        String ref = TransactionRefs.newRef();

        assertThat(ref).matches("TXN-[0-9a-f]{32}").hasSize(36);
        assertThat(TransactionRefs.isWellFormed(ref)).isTrue();
    }

    @Test
    void refsDoNotRepeat() {
        Set<String> refs = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            refs.add(TransactionRefs.newRef());
        }
        assertThat(refs).hasSize(10_000);
    }
}

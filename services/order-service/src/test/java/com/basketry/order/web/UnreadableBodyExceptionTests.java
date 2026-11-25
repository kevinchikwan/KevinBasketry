package com.basketry.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The 400's path shows only CheckoutRequest's own field names. order-service
 * does not refuse repeated fields yet (ROADMAP F), so a path with another name
 * cannot reach the endpoint today; with repeated-field detection on, a repeat
 * inside an unknown field's value puts that field's name, which could be a
 * card number, in Jackson's path (found in promotion-service's A6).
 */
class UnreadableBodyExceptionTests {

    private static final String SENTINEL = "4929117300568841";

    /** Mutation: every name in the path shown. */
    @Test
    void aNameThatIsNotARequestFieldIsNeverShown() {
        JsonMapper strict = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        JacksonException failure = catchThrowableOfType(JacksonException.class, () -> strict.readValue(
                "{\"lines\":[{\"productId\":10,\"quantity\":1,\"a" + SENTINEL + "\":{\"x\":1,\"x\":2}}]}",
                CheckoutRequest.class));

        assertThat(failure.getPath()).isNotEmpty();
        assertThat(new UnreadableBodyException(failure).getMessage())
                .isEqualTo(OrderExceptionHandler.UNREADABLE + " At: lines[0].?.")
                .doesNotContain(SENTINEL);
    }
}

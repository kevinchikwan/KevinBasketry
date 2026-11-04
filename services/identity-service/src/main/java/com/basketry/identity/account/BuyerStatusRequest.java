package com.basketry.identity.account;

import jakarta.validation.constraints.NotNull;

/**
 * Body of PATCH /accounts/{id}/buyer-status. A {@code Boolean} object, not a
 * primitive: {@code {}} or {@code {"buyerActive": null}} must be a 400, never
 * a silent default of {@code false} that would deactivate the account.
 */
public record BuyerStatusRequest(@NotNull Boolean buyerActive) {
}

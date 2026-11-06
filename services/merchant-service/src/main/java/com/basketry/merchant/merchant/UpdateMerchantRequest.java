package com.basketry.merchant.merchant;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Body of PATCH /merchants/{code} (FR-A4). A class rather than a record
 * because it has to tell a missing field from an explicit null, and has to
 * see unknown fields:
 *
 * <ul>
 *   <li>{@code commissionRate}, {@code kycVerified} and {@code active} are each
 *       optional, but at least one must be present.</li>
 *   <li>An explicit {@code null} is a 400, never "no change" or "false".</li>
 *   <li>{@code kycVerified} and {@code active} must be real JSON booleans.</li>
 *   <li>Any other field is a 400 naming it, so Admin never believes a change
 *       was applied when it was not.</li>
 * </ul>
 */
public class UpdateMerchantRequest {

    @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2)
    private BigDecimal commissionRate;
    private boolean commissionRatePresent;

    private Boolean kycVerified;
    private boolean kycVerifiedPresent;

    private Boolean active;
    private boolean activePresent;

    private final List<String> unknownFields = new ArrayList<>();

    @JsonDeserialize(using = StrictDecimalDeserializer.class)
    public void setCommissionRate(BigDecimal commissionRate) {
        this.commissionRate = commissionRate;
        this.commissionRatePresent = true;
    }

    @JsonDeserialize(using = StrictBooleanDeserializer.class)
    public void setKycVerified(Boolean kycVerified) {
        this.kycVerified = kycVerified;
        this.kycVerifiedPresent = true;
    }

    @JsonDeserialize(using = StrictBooleanDeserializer.class)
    public void setActive(Boolean active) {
        this.active = active;
        this.activePresent = true;
    }

    @JsonAnySetter
    void unknownField(String name, Object ignored) {
        unknownFields.add(name);
    }

    /**
     * The rules above that bean validation cannot express. Throws
     * {@link InvalidRequestBodyException} (400).
     */
    void checkShape() {
        if (!unknownFields.isEmpty()) {
            throw new InvalidRequestBodyException(
                    "Unknown or not updatable field(s): " + String.join(", ", unknownFields)
                            + ". Only commissionRate, kycVerified and active can be changed.");
        }
        if (!commissionRatePresent && !kycVerifiedPresent && !activePresent) {
            throw new InvalidRequestBodyException("Give at least one of commissionRate, kycVerified and active.");
        }
        List<String> nulls = new ArrayList<>();
        if (commissionRatePresent && commissionRate == null) {
            nulls.add("commissionRate");
        }
        if (kycVerifiedPresent && kycVerified == null) {
            nulls.add("kycVerified");
        }
        if (activePresent && active == null) {
            nulls.add("active");
        }
        if (!nulls.isEmpty()) {
            throw new InvalidRequestBodyException("Field(s) must not be null: " + String.join(", ", nulls) + ".");
        }
    }

    boolean hasActive() {
        return activePresent;
    }

    /** Everything except {@code active}, which goes through {@link Merchant#changeActive}. */
    void applyFieldsTo(Merchant merchant) {
        if (commissionRatePresent) {
            merchant.setCommissionRate(commissionRate);
        }
        if (kycVerifiedPresent) {
            merchant.setKycVerified(kycVerified);
        }
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }

    public Boolean getKycVerified() {
        return kycVerified;
    }

    public Boolean getActive() {
        return active;
    }
}

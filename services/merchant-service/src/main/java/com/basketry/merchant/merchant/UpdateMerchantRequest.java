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
 *   <li>{@code commissionRate} and {@code kycVerified} are each optional,
 *       but at least one must be present.</li>
 *   <li>An explicit {@code null} is a 400, never "no change" or "false".</li>
 *   <li>Any other field is a 400 naming it, so Admin never believes a change
 *       was applied when it was not. That includes {@code active} until it
 *       is added together with the status event (Stage B).</li>
 * </ul>
 */
public class UpdateMerchantRequest {

    @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2)
    private BigDecimal commissionRate;
    private boolean commissionRatePresent;

    private Boolean kycVerified;
    private boolean kycVerifiedPresent;

    private final List<String> unknownFields = new ArrayList<>();

    @JsonDeserialize(using = StrictDecimalDeserializer.class)
    public void setCommissionRate(BigDecimal commissionRate) {
        this.commissionRate = commissionRate;
        this.commissionRatePresent = true;
    }

    public void setKycVerified(Boolean kycVerified) {
        this.kycVerified = kycVerified;
        this.kycVerifiedPresent = true;
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
                            + ". Only commissionRate and kycVerified can be changed.");
        }
        if (!commissionRatePresent && !kycVerifiedPresent) {
            throw new InvalidRequestBodyException("Give at least one of commissionRate and kycVerified.");
        }
        List<String> nulls = new ArrayList<>();
        if (commissionRatePresent && commissionRate == null) {
            nulls.add("commissionRate");
        }
        if (kycVerifiedPresent && kycVerified == null) {
            nulls.add("kycVerified");
        }
        if (!nulls.isEmpty()) {
            throw new InvalidRequestBodyException("Field(s) must not be null: " + String.join(", ", nulls) + ".");
        }
    }

    void applyTo(Merchant merchant) {
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
}

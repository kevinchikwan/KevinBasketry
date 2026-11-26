package com.basketry.merchant.merchant;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Body of POST /merchants (FR-A1). {@code name}, {@code category} and
 * {@code loginEmail} are trimmed before validation. {@code initialPassword}
 * is only passed on to identity-service, which needs it only if no account
 * exists for the email; it is never stored or logged.
 *
 * <p>Any other field is a 400 naming it (the same rule as PATCH), so Admin
 * never believes a value was applied when it was not. That includes
 * {@code active}: a new merchant always starts active. Unknown fields are
 * collected in {@code unknownFields} and refused by {@link #rejectUnknownFields()}.
 */
public record CreateMerchantRequest(

        @NotBlank @Size(max = 100)
        String name,

        @NotBlank @Size(max = 50)
        String category,

        @NotNull
        @JsonDeserialize(using = StrictBooleanDeserializer.class)
        Boolean kycVerified,

        @NotNull
        @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2)
        @JsonDeserialize(using = StrictDecimalDeserializer.class)
        BigDecimal commissionRate,

        @NotBlank @Email @Size(max = 255)
        String loginEmail,

        String initialPassword,

        @JsonAnySetter
        Map<String, Object> unknownFields) {

    public CreateMerchantRequest {
        name = strip(name);
        category = strip(category);
        loginEmail = strip(loginEmail);
        unknownFields = unknownFields == null ? new LinkedHashMap<>() : unknownFields;
    }

    public CreateMerchantRequest(String name, String category, Boolean kycVerified, BigDecimal commissionRate,
                                 String loginEmail, String initialPassword) {
        this(name, category, kycVerified, commissionRate, loginEmail, initialPassword, null);
    }

    /**
     * Throws {@link InvalidRequestBodyException} (400) naming every field this
     * endpoint does not accept, if its name may be shown (UnknownFieldNames);
     * any other is only counted.
     */
    void rejectUnknownFields() {
        if (!unknownFields.isEmpty()) {
            String hint = unknownFields.containsKey("active") ? " A new merchant always starts active." : "";
            // Sorted: Jackson does not keep the request's field order in this map.
            TreeSet<String> names = new TreeSet<>(unknownFields.keySet());
            List<String> shown = UnknownFieldNames.shownOf(names);
            long hidden = UnknownFieldNames.hiddenOf(names);
            String named = shown.isEmpty() ? "" : "Unknown field(s): " + String.join(", ", shown) + ".";
            String counted = hidden == 0 ? ""
                    : (named.isEmpty() ? "" : " ") + hidden + " unknown field(s) with a name that is not shown.";
            throw new InvalidRequestBodyException(named + counted + hint);
        }
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    /**
     * Keeps the name, the login email, the plaintext password and the unknown
     * fields (names and values) out of any log line that prints this object.
     */
    @Override
    public String toString() {
        return "CreateMerchantRequest[name=***, category=" + category + ", kycVerified=" + kycVerified
                + ", commissionRate=" + commissionRate + ", loginEmail=***, initialPassword=***"
                + ", unknownFields=" + unknownFields.size() + "]";
    }
}

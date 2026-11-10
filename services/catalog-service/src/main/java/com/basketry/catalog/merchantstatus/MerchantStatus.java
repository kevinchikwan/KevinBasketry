package com.basketry.catalog.merchantstatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The local copy of one merchant's {@code active} (DESIGN.md Section 2,
 * catalog-service). Read-only here: written only by the conditional MERGE of
 * {@link MerchantStatusRepository#apply}. A merchant with no row is active at
 * version 0.
 */
@Entity
@Table(name = "merchant_status")
public class MerchantStatus {

    @Id
    private Long merchantId;

    @Column(updatable = false, insertable = false)
    private boolean active;

    @Column(updatable = false, insertable = false)
    private long version;

    @Column(updatable = false, insertable = false)
    private Instant updatedAt;

    protected MerchantStatus() {
    }

    public long getMerchantId() {
        return merchantId;
    }

    public boolean isActive() {
        return active;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

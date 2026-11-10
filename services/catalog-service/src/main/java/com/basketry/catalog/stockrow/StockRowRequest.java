package com.basketry.catalog.stockrow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * The outbox row of one product's stock-row creation (DESIGN.md Section 2,
 * catalog-service). Inserted with the product, in its transaction; afterwards
 * changed only by the conditional updates of {@link StockRowRequestRepository},
 * never by writing the entity.
 */
@Entity
@Table(name = "stock_row_request")
public class StockRowRequest implements Persistable<Long> {

    @Id
    private Long productId;

    @Column(updatable = false)
    private long merchantId;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private StockRowStatus status;

    @Column(updatable = false)
    private int attempts;

    @Column(updatable = false)
    private String lastError;

    @Column(updatable = false)
    private Instant createdAt;

    @Column(updatable = false)
    private Instant completedAt;

    /** The id is assigned, so Spring Data cannot tell a new row by a null id: save must insert, never merge. */
    @Transient
    private boolean isNew = true;

    protected StockRowRequest() {
    }

    public StockRowRequest(long productId, long merchantId, Instant createdAt) {
        this.productId = productId;
        this.merchantId = merchantId;
        this.status = StockRowStatus.PENDING;
        this.createdAt = createdAt;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    @Override
    public Long getId() {
        return productId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public long getProductId() {
        return productId;
    }

    public long getMerchantId() {
        return merchantId;
    }

    public StockRowStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}

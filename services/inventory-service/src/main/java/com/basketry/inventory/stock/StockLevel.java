package com.basketry.inventory.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * The stock still available for one product, and its owner (DESIGN.md Section
 * 2, inventory-service). Inserted once and never deleted; the owner never
 * changes. {@code quantity} changes only through the conditional statements of
 * {@link StockLevelRepository}, never by writing the entity.
 */
@Entity
@Table(name = "stock_level")
public class StockLevel implements Persistable<Long> {

    @Id
    private Long productId;

    @Column(updatable = false)
    private long merchantId;

    @Column(updatable = false)
    private long quantity;

    @Column(updatable = false)
    private Instant createdAt;

    @Column(updatable = false)
    private Instant updatedAt;

    /** The id is assigned, so Spring Data cannot tell a new row by a null id: save must insert, never merge. */
    @Transient
    private boolean isNew = true;

    protected StockLevel() {
    }

    StockLevel(long productId, long merchantId, Instant createdAt) {
        this.productId = productId;
        this.merchantId = merchantId;
        this.quantity = 0;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
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

    public long getQuantity() {
        return quantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    StockSnapshot snapshot() {
        return new StockSnapshot(productId, merchantId, quantity);
    }
}

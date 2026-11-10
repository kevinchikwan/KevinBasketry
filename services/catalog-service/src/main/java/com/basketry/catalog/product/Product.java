package com.basketry.catalog.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A merchant's product (DESIGN.md Section 2, catalog-service). Written only by
 * {@link ProductService}: created once, changed only under the row lock
 * ({@link ProductRepository#findByIdForUpdate}), never deleted. The owner never
 * changes.
 */
@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "product_id")
    @SequenceGenerator(name = "product_id", sequenceName = "product_id_seq", allocationSize = 1)
    private Long id;

    @Column(updatable = false)
    private long merchantId;

    private String name;

    private String description;

    private String category;

    private BigDecimal price;

    private boolean active;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    protected Product() {
    }

    Product(CreateProductCommand command, Instant now) {
        this.merchantId = command.merchantId();
        this.name = command.name();
        this.description = command.description();
        this.category = command.category();
        this.price = command.price();
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public long getMerchantId() {
        return merchantId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void setName(String name) {
        this.name = name;
    }

    void setDescription(String description) {
        this.description = description;
    }

    void setCategory(String category) {
        this.category = category;
    }

    void setPrice(BigDecimal price) {
        this.price = price;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    void touch(Instant now) {
        this.updatedAt = now;
    }

    ProductView view() {
        return new ProductView(id, merchantId, name, description, category, price, active, createdAt, updatedAt);
    }
}

package com.basketry.identity.account;

import com.basketry.identity.Emails;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * A buyer/merchant login (DESIGN.md Section 2, identity-service).
 *
 * <ul>
 *   <li>{@code buyerActive} is the account-level login gate.</li>
 *   <li>{@code merchantId} / {@code merchantActive} add the MERCHANT capability;
 *       {@code merchantActive} is a cached copy of merchant-service's
 *       {@code Merchant.active}, seeded once at link time and afterwards
 *       changed only by the {@code merchant.status.changed} event.</li>
 * </ul>
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Always stored lowercase; uniqueness is therefore case-insensitive. */
    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "buyer_active", nullable = false)
    private boolean buyerActive;

    /** Reference to merchant-service's Merchant; not a database foreign key. */
    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "merchant_active", nullable = false)
    private boolean merchantActive;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
        // for JPA
    }

    /** A new account is an active buyer with no merchant link. */
    public Account(String email, String passwordHash) {
        this.email = Emails.normalize(email);
        this.passwordHash = passwordHash;
        this.buyerActive = true;
        this.merchantId = null;
        this.merchantActive = false;
        // Truncated to what the database stores, so a response built from this object
        // matches what a later read of the row returns.
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isBuyerActive() {
        return buyerActive;
    }

    /**
     * Admin activation/deactivation (FR-A6). Does not touch the merchant link,
     * and does not revoke tokens already issued.
     */
    public void setBuyerActive(boolean buyerActive) {
        this.buyerActive = buyerActive;
    }

    /**
     * Makes the merchant link (merchant-link upsert, DESIGN.md Section 3).
     * {@code merchantActive = true} is set only here, when the link is first
     * made; afterwards only the merchant.status.changed event changes it.
     */
    public void linkMerchant(long merchantId) {
        this.merchantId = merchantId;
        this.merchantActive = true;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public boolean isMerchantActive() {
        return merchantActive;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

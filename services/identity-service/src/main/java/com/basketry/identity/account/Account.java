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
import org.hibernate.annotations.DynamicUpdate;

/**
 * A buyer/merchant login (DESIGN.md Section 2, identity-service).
 *
 * <ul>
 *   <li>{@code buyerActive} is the account-level login gate.</li>
 *   <li>{@code merchantId} / {@code merchantActive} add the MERCHANT capability;
 *       {@code merchantActive} is a cached copy of merchant-service's
 *       {@code Merchant.active}, seeded once at link time and afterwards
 *       changed only by the {@code merchant.status.changed} event.</li>
 *   <li>{@code merchantStatusVersion} is the version of the last event applied.</li>
 * </ul>
 *
 * <p>Each writer owns separate columns, and {@code @DynamicUpdate} makes an
 * update write only the columns it changed, so overlapping writers cannot undo
 * each other (DESIGN.md Section 2):
 * <ul>
 *   <li>buyer-status PATCH: {@code buyer_active}</li>
 *   <li>merchant link and the status consumer: {@code merchant_id},
 *       {@code merchant_active}, {@code merchant_status_version}. They never
 *       overlap: the consumer's update matches a row only once its link is
 *       committed, and a link is made only once.</li>
 * </ul>
 * Any new writer of a column that another writer already owns must take a row
 * lock, because this annotation does not protect two writers of one column.
 */
@Entity
@Table(name = "account")
@DynamicUpdate
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

    /** Changed only by {@link AccountRepository#applyMerchantStatus} after the link. */
    @Column(name = "merchant_status_version", nullable = false)
    private long merchantStatusVersion;

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
     * made; afterwards only the merchant.status.changed event changes it. A
     * newly onboarded merchant is active at version 0 (DESIGN.md Section 5).
     */
    public void linkMerchant(long merchantId) {
        this.merchantId = merchantId;
        this.merchantActive = true;
        this.merchantStatusVersion = 0;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public boolean isMerchantActive() {
        return merchantActive;
    }

    public long getMerchantStatusVersion() {
        return merchantStatusVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

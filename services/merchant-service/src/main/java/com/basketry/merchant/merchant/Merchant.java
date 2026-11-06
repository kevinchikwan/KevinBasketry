package com.basketry.merchant.merchant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import org.springframework.data.domain.Persistable;

/**
 * A merchant (DESIGN.md Section 2, merchant-service). The id comes from the
 * {@code merchant_id_seq} sequence before the row is built, so
 * {@code merchantCode} is set in the constructor and the row is written with
 * one INSERT, never an insert followed by an update.
 */
@Entity
@Table(name = "merchant")
public class Merchant implements Persistable<Long> {

    @Id
    private Long id;

    @Column(updatable = false)
    private String merchantCode;

    private String name;

    private String category;

    private boolean kycVerified;

    private boolean active;

    /**
     * Counts changes of {@code active}, published as the event's {@code version}.
     * Not a JPA {@code @Version}: commission and KYC changes must not move it.
     */
    private long statusVersion;

    private BigDecimal commissionRate;

    private String loginEmail;

    @Enumerated(EnumType.STRING)
    private AccountLinkStatus accountLinkStatus;

    @Column(updatable = false)
    private Instant createdAt;

    /** The id is assigned, not generated, so Spring Data cannot tell on its own that the row is new. */
    @Transient
    private boolean isNew;

    protected Merchant() {
    }

    /**
     * A newly onboarded merchant: active, with its login not linked yet.
     *
     * @param id         the next value of {@code merchant_id_seq}
     * @param loginEmail already normalized to lowercase
     */
    public Merchant(long id, String name, String category, boolean kycVerified, BigDecimal commissionRate,
                    String loginEmail, Instant createdAt) {
        this.id = id;
        this.merchantCode = codeFor(id);
        this.name = name;
        this.category = category;
        this.kycVerified = kycVerified;
        this.active = true;
        this.commissionRate = commissionRate.setScale(2);
        this.loginEmail = loginEmail;
        this.accountLinkStatus = AccountLinkStatus.PENDING;
        this.createdAt = createdAt;
        this.isNew = true;
    }

    /** MCH-001 .. MCH-999, then MCH-1000 and up: zero-padded to at least three digits. */
    static String codeFor(long id) {
        return String.format(Locale.ROOT, "MCH-%03d", id);
    }

    /**
     * A merchant-link call succeeded. {@code loginEmail} changes only here,
     * so a failed attempt with a different email leaves the stored one as it was.
     */
    void markLinked(String linkedEmail) {
        this.accountLinkStatus = AccountLinkStatus.LINKED;
        this.loginEmail = linkedEmail;
    }

    /**
     * The only way to change {@code active}, so the version cannot be forgotten.
     *
     * @return true if the value changed; the caller then writes the outbox row
     */
    boolean changeActive(boolean active) {
        if (this.active == active) {
            return false;
        }
        this.active = active;
        this.statusVersion++;
        return true;
    }

    void setCommissionRate(BigDecimal commissionRate) {
        this.commissionRate = commissionRate.setScale(2);
    }

    void setKycVerified(boolean kycVerified) {
        this.kycVerified = kycVerified;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public Long getId() {
        return id;
    }

    public String getMerchantCode() {
        return merchantCode;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public boolean isKycVerified() {
        return kycVerified;
    }

    public boolean isActive() {
        return active;
    }

    public long getStatusVersion() {
        return statusVersion;
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }

    public String getLoginEmail() {
        return loginEmail;
    }

    public AccountLinkStatus getAccountLinkStatus() {
        return accountLinkStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

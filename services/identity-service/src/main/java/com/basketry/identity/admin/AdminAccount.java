package com.basketry.identity.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A platform admin (DESIGN.md Section 2). Rows are created only by Flyway
 * migrations; no application code creates or modifies them, so this entity
 * is read-only by design: no public constructor and no setters.
 */
@Entity
@Table(name = "admin_account")
public class AdminAccount {

    @Id
    private Long id;

    /** Always stored lowercase; must not also exist in the account table. */
    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AdminAccount() {
        // for JPA
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}

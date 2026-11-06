package com.authservice.persistence;

import com.authservice.domain.AccountStatus;
import com.authservice.domain.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users", schema = "public")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAccount {

    public static final int MAX_FAILED_ATTEMPTS = 10;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "identity_id")
    private UUID identityId;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "normalized_email", nullable = false, length = 320)
    private String normalizedEmail;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "failed_login_count", nullable = false)
    @Builder.Default
    private int failedLoginCount = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isAccountNonLocked(Instant now) {
        if (status == AccountStatus.LOCKED) {
            if (lockedUntil != null && now.isAfter(lockedUntil)) {
                return true; // Lockout expired
            }
            return false;
        }
        return true;
    }

    public boolean isAvailable(Instant now) {
        if (status == AccountStatus.PENDING_EMAIL || status == AccountStatus.DISABLED) {
            return false;
        }
        return isAccountNonLocked(now);
    }

    public void recordFailedLogin(Instant now) {
        this.failedLoginCount++;
        if (this.failedLoginCount >= MAX_FAILED_ATTEMPTS) {
            this.status = AccountStatus.LOCKED;
            this.lockedUntil = now.plus(LOCK_DURATION);
        }
    }

    public void recordSuccessfulLogin(Instant now) {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        if (this.status == AccountStatus.LOCKED) {
            this.status = AccountStatus.ACTIVE;
        }
        this.lastLoginAt = now;
    }
}

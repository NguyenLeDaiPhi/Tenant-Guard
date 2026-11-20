package com.authservice.persistence;

import com.authservice.domain.IdentityStatus;
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

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

@Entity
@Table(name = "user_identities", schema = "public")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserIdentity {

    public static final int MAX_FAILED_ATTEMPTS = 10;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "normalized_email", nullable = false, length = 320, unique = true)
    private String normalizedEmail;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private IdentityStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "failed_login_count", nullable = false)
    @Builder.Default
    private int failedLoginCount = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

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

    public boolean isVerified() {
        return status != IdentityStatus.PENDING_EMAIL && emailVerifiedAt != null;
    }

    public boolean isAvailable(Instant now) {
        if (status == IdentityStatus.PENDING_EMAIL || status == IdentityStatus.DISABLED) {
            return false;
        }
        if (status == IdentityStatus.LOCKED) {
            return lockedUntil != null && now.isAfter(lockedUntil);
        }
        return status == IdentityStatus.VERIFIED && emailVerifiedAt != null;
    }

    public void verify(Instant now) {
        status = IdentityStatus.VERIFIED;
        emailVerifiedAt = now;
    }

    public void recordFailedLogin(Instant now) {
        failedLoginCount++;
        if (failedLoginCount >= MAX_FAILED_ATTEMPTS) {
            status = IdentityStatus.LOCKED;
            lockedUntil = now.plus(LOCK_DURATION);
        }
    }

    public void recordSuccessfulLogin(Instant now) {
        failedLoginCount = 0;
        lockedUntil = null;
        if (status == IdentityStatus.LOCKED) {
            status = IdentityStatus.VERIFIED;
        }
        lastLoginAt = now;
    }
}

package com.authservice.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_verification_codes", schema = "public")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailVerificationCode {

    public static final int MAX_FAILED_ATTEMPTS = 5;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "normalized_email", nullable = false, length = 320, updatable = false)
    private String normalizedEmail;

    @Column(name = "identity_id", updatable = false)
    private UUID identityId;

    @Column(name = "code_hash", nullable = false, unique = true, length = 64, updatable = false)
    private String codeHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "failed_attempts", nullable = false)
    @Builder.Default
    private int failedAttempts = 0;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isUsable(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt) && failedAttempts < MAX_FAILED_ATTEMPTS;
    }

    public void recordFailedAttempt(Instant now) {
        failedAttempts++;
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            consumedAt = now;
        }
    }

    public void consume(Instant now) {
        consumedAt = now;
    }
}

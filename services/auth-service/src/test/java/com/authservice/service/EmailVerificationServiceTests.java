package com.authservice.service;

import com.authservice.domain.IdentityStatus;
import com.authservice.persistence.EmailVerificationChallenge;
import com.authservice.persistence.EmailVerificationChallengeRepository;
import com.authservice.persistence.UserIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailVerificationServiceTests {

    private static final String EMAIL = "member@example.com";
    private static final String CODE = "a".repeat(64);
    private static final String CLIENT_IP = "192.0.2.1";

    private EmailVerificationChallengeRepository verificationRepository;
    private RegistrationRateLimiter rateLimiter;
    private RegistrationEmailLock registrationEmailLock;
    private EmailVerificationService verificationService;

    @BeforeEach
    void setUp() {
        verificationRepository = mock(EmailVerificationChallengeRepository.class);
        rateLimiter = mock(RegistrationRateLimiter.class);
        registrationEmailLock = mock(RegistrationEmailLock.class);
        verificationService = new EmailVerificationService(
                verificationRepository,
                rateLimiter,
                registrationEmailLock
        );
    }

    @Test
    void createsVerifiedIdentityAndDeletesTemporaryChallenge() {
        EmailVerificationChallenge challenge = verificationCode(Instant.now().plusSeconds(300));
        when(verificationRepository.findAllPendingByNormalizedEmailForUpdate(EMAIL))
                .thenReturn(List.of(challenge));

        assertThat(verificationService.verify(EMAIL, CODE, CLIENT_IP)).isTrue();

        UserIdentity identity = challenge.getIdentity();
        assertThat(identity.getStatus()).isEqualTo(IdentityStatus.VERIFIED);
        assertThat(identity.getEmailVerifiedAt()).isNotNull();
        verify(verificationRepository).delete(challenge);
    }

    @Test
    void incorrectCodeIncrementsAttemptsWithoutVerifyingIdentity() {
        EmailVerificationChallenge challenge = verificationCode(Instant.now().plusSeconds(300));
        when(verificationRepository.findAllPendingByNormalizedEmailForUpdate(EMAIL))
                .thenReturn(List.of(challenge));

        assertThat(verificationService.verify(EMAIL, "b".repeat(64), CLIENT_IP)).isFalse();

        assertThat(challenge.getFailedAttempts()).isEqualTo(1);
        verify(verificationRepository).save(challenge);
        assertThat(challenge.getIdentity().getStatus()).isEqualTo(IdentityStatus.PENDING_EMAIL);
    }

    @Test
    void maxFailedAttemptsDeleteChallengeAndRejectAttempt() {
        EmailVerificationChallenge challenge = verificationCode(Instant.now().plusSeconds(300));
        challenge.setFailedAttempts(4);
        when(verificationRepository.findAllPendingByNormalizedEmailForUpdate(EMAIL))
                .thenReturn(List.of(challenge));

        assertThat(verificationService.verify(EMAIL, "c".repeat(64), CLIENT_IP)).isFalse();

        assertThat(challenge.getFailedAttempts()).isEqualTo(5);
        assertThat(challenge.getConsumedAt()).isNotNull();
        verify(verificationRepository).delete(challenge);
    }

    @Test
    void expiredChallengeIsRejectedAndDeleted() {
        EmailVerificationChallenge challenge = verificationCode(Instant.now().minusSeconds(1));
        when(verificationRepository.findAllPendingByNormalizedEmailForUpdate(EMAIL))
                .thenReturn(List.of(challenge));

        assertThat(verificationService.verify(EMAIL, CODE, CLIENT_IP)).isFalse();

        verify(verificationRepository).delete(challenge);
    }

    @Test
    void missingChallengeDoesNotCreateIdentity() {
        when(verificationRepository.findAllPendingByNormalizedEmailForUpdate(EMAIL))
                .thenReturn(List.of());
        assertThat(verificationService.verify(EMAIL, CODE, CLIENT_IP)).isFalse();

        verify(verificationRepository).findAllPendingByNormalizedEmailForUpdate(EMAIL);
    }

    @Test
    void invalidCodeFormatIsRejectedBeforeIdentityLookup() {
        assertThat(verificationService.verify(EMAIL, "not-a-code", CLIENT_IP)).isFalse();

        verify(verificationRepository, never()).findAllPendingByNormalizedEmailForUpdate(any());
    }

    @Test
    void rateLimitedVerificationDoesNotLookUpIdentity() {
        doThrow(new RateLimitExceededException())
                .when(rateLimiter).checkVerification(CLIENT_IP, EMAIL);

        assertThatThrownBy(() -> verificationService.verify(EMAIL, CODE, CLIENT_IP))
                .isInstanceOf(RateLimitExceededException.class);

        verify(verificationRepository, never()).findAllPendingByNormalizedEmailForUpdate(any());
    }

    private static EmailVerificationChallenge verificationCode(Instant expiresAt) {
        Instant now = Instant.now();
        UserIdentity identity = UserIdentity.builder()
                .id(UUID.randomUUID())
                .email(EMAIL)
                .normalizedEmail(EMAIL)
                .passwordHash("stored-password-hash")
                .status(IdentityStatus.PENDING_EMAIL)
                .createdAt(now.minusSeconds(60))
                .updatedAt(now.minusSeconds(60))
                .build();
        return EmailVerificationChallenge.builder()
                .id(UUID.randomUUID())
                .identity(identity)
                .codeHash(VerificationCodeHasher.hash(CODE))
                .createdAt(now.minusSeconds(10))
                .expiresAt(expiresAt)
                .build();
    }
}

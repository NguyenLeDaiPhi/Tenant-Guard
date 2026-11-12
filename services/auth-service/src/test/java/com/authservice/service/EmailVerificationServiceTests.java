package com.authservice.service;

import com.authservice.domain.IdentityStatus;
import com.authservice.persistence.EmailVerificationCode;
import com.authservice.persistence.EmailVerificationCodeRepository;
import com.authservice.persistence.UserIdentity;
import com.authservice.persistence.UserIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    private EmailVerificationCodeRepository verificationRepository;
    private UserIdentityRepository identityRepository;
    private RegistrationRateLimiter rateLimiter;
    private RegistrationEmailLock registrationEmailLock;
    private EmailVerificationService verificationService;

    @BeforeEach
    void setUp() {
        verificationRepository = mock(EmailVerificationCodeRepository.class);
        identityRepository = mock(UserIdentityRepository.class);
        rateLimiter = mock(RegistrationRateLimiter.class);
        registrationEmailLock = mock(RegistrationEmailLock.class);
        verificationService = new EmailVerificationService(
                verificationRepository,
                identityRepository,
                rateLimiter,
                registrationEmailLock
        );
    }

    @Test
    void createsVerifiedIdentityAndDeletesTemporaryChallenge() {
        EmailVerificationCode challenge = verificationCode(Instant.now().plusSeconds(300));
        when(verificationRepository.findAllPendingByNormalizedEmailForUpdate(EMAIL))
                .thenReturn(List.of(challenge));

        assertThat(verificationService.verify(EMAIL, CODE, CLIENT_IP)).isTrue();

        var identityCaptor = org.mockito.ArgumentCaptor.forClass(UserIdentity.class);
        verify(identityRepository).save(identityCaptor.capture());
        UserIdentity identity = identityCaptor.getValue();
        assertThat(identity.getStatus()).isEqualTo(IdentityStatus.VERIFIED);
        assertThat(identity.getEmailVerifiedAt()).isNotNull();
        assertThat(identity.getPasswordHash()).isEqualTo("temporary-password-hash");
        verify(verificationRepository).delete(challenge);
        verify(verificationRepository, never()).save(challenge);
    }

    @Test
    void incorrectCodeIncrementsAttemptsWithoutCreatingIdentity() {
        EmailVerificationCode challenge = verificationCode(Instant.now().plusSeconds(300));
        when(verificationRepository.findAllPendingByNormalizedEmailForUpdate(EMAIL))
                .thenReturn(List.of(challenge));

        assertThat(verificationService.verify(EMAIL, "b".repeat(64), CLIENT_IP)).isFalse();

        assertThat(challenge.getFailedAttempts()).isEqualTo(1);
        verify(verificationRepository).save(challenge);
        verify(identityRepository, never()).save(any(UserIdentity.class));
    }

    @Test
    void maxFailedAttemptsDeleteChallengeAndRejectAttempt() {
        EmailVerificationCode challenge = verificationCode(Instant.now().plusSeconds(300));
        challenge.setFailedAttempts(4);
        when(verificationRepository.findAllPendingByNormalizedEmailForUpdate(EMAIL))
                .thenReturn(List.of(challenge));

        assertThat(verificationService.verify(EMAIL, "c".repeat(64), CLIENT_IP)).isFalse();

        assertThat(challenge.getFailedAttempts()).isEqualTo(5);
        verify(verificationRepository).delete(challenge);
        verify(identityRepository, never()).save(any(UserIdentity.class));
    }

    @Test
    void expiredChallengeIsRejectedAndDeleted() {
        EmailVerificationCode challenge = verificationCode(Instant.now().minusSeconds(1));
        when(verificationRepository.findAllPendingByNormalizedEmailForUpdate(EMAIL))
                .thenReturn(List.of(challenge));

        assertThat(verificationService.verify(EMAIL, CODE, CLIENT_IP)).isFalse();

        verify(verificationRepository).delete(challenge);
        verify(identityRepository, never()).save(any(UserIdentity.class));
    }

    @Test
    void missingChallengeDoesNotCreateIdentity() {
        when(verificationRepository.findAllPendingByNormalizedEmailForUpdate(EMAIL))
                .thenReturn(List.of());
        assertThat(verificationService.verify(EMAIL, CODE, CLIENT_IP)).isFalse();

        verify(identityRepository, never()).findByNormalizedEmailForUpdate(any());
    }

    @Test
    void invalidCodeFormatIsRejectedBeforeIdentityLookup() {
        assertThat(verificationService.verify(EMAIL, "not-a-code", CLIENT_IP)).isFalse();

        verify(identityRepository, never()).findByNormalizedEmailForUpdate(any());
    }

    @Test
    void rateLimitedVerificationDoesNotLookUpIdentity() {
        doThrow(new RateLimitExceededException())
                .when(rateLimiter).checkVerification(CLIENT_IP, EMAIL);

        assertThatThrownBy(() -> verificationService.verify(EMAIL, CODE, CLIENT_IP))
                .isInstanceOf(RateLimitExceededException.class);

        verify(identityRepository, never()).findByNormalizedEmailForUpdate(any());
        verify(verificationRepository, never()).findAllPendingByNormalizedEmailForUpdate(any());
    }

    private static EmailVerificationCode verificationCode(Instant expiresAt) {
        return EmailVerificationCode.builder()
                .id(UUID.randomUUID())
                .normalizedEmail(EMAIL)
                .codeHash(VerificationCodeHasher.hash(CODE))
                .temporaryPasswordHash("temporary-password-hash")
                .createdAt(Instant.now().minusSeconds(10))
                .expiresAt(expiresAt)
                .build();
    }
}

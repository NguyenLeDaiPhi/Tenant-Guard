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
    private EmailVerificationService verificationService;

    @BeforeEach
    void setUp() {
        verificationRepository = mock(EmailVerificationCodeRepository.class);
        identityRepository = mock(UserIdentityRepository.class);
        rateLimiter = mock(RegistrationRateLimiter.class);
        verificationService = new EmailVerificationService(
                verificationRepository,
                identityRepository,
                rateLimiter
        );
    }

    @Test
    void verifiesIdentityEmailWithoutActivatingTenantMemberships() {
        UserIdentity identity = pendingIdentity();
        EmailVerificationCode challenge = verificationCode(identity.getId(), Instant.now().plusSeconds(300));
        when(identityRepository.findByNormalizedEmailForUpdate(EMAIL)).thenReturn(Optional.of(identity));
        when(verificationRepository.findAllActiveByIdentityIdForUpdate(identity.getId()))
                .thenReturn(List.of(challenge));

        assertThat(verificationService.verify(EMAIL, CODE, CLIENT_IP)).isTrue();

        assertThat(identity.getStatus()).isEqualTo(IdentityStatus.VERIFIED);
        assertThat(identity.getEmailVerifiedAt()).isNotNull();
        assertThat(challenge.getConsumedAt()).isNotNull();
        verify(identityRepository).save(identity);
        verify(verificationRepository).save(challenge);
    }

    @Test
    void incorrectCodeIncrementsAttemptsAndLeavesIdentityPending() {
        UserIdentity identity = pendingIdentity();
        EmailVerificationCode challenge = verificationCode(identity.getId(), Instant.now().plusSeconds(300));
        when(identityRepository.findByNormalizedEmailForUpdate(EMAIL)).thenReturn(Optional.of(identity));
        when(verificationRepository.findAllActiveByIdentityIdForUpdate(identity.getId()))
                .thenReturn(List.of(challenge));

        assertThat(verificationService.verify(EMAIL, "b".repeat(64), CLIENT_IP)).isFalse();

        assertThat(challenge.getFailedAttempts()).isEqualTo(1);
        assertThat(identity.getStatus()).isEqualTo(IdentityStatus.PENDING_EMAIL);
        assertThat(identity.getEmailVerifiedAt()).isNull();
        verify(verificationRepository).save(challenge);
        verify(identityRepository, never()).save(any(UserIdentity.class));
    }

    @Test
    void maxFailedAttemptsConsumesChallengeAndRejectsAttempt() {
        UserIdentity identity = pendingIdentity();
        EmailVerificationCode challenge = verificationCode(identity.getId(), Instant.now().plusSeconds(300));
        challenge.setFailedAttempts(4);
        when(identityRepository.findByNormalizedEmailForUpdate(EMAIL)).thenReturn(Optional.of(identity));
        when(verificationRepository.findAllActiveByIdentityIdForUpdate(identity.getId()))
                .thenReturn(List.of(challenge));

        assertThat(verificationService.verify(EMAIL, "c".repeat(64), CLIENT_IP)).isFalse();

        assertThat(challenge.getFailedAttempts()).isEqualTo(5);
        assertThat(challenge.getConsumedAt()).isNotNull();
        verify(verificationRepository).save(challenge);
        verify(identityRepository, never()).save(any(UserIdentity.class));
    }

    @Test
    void expiredChallengeIsRejectedAndConsumed() {
        UserIdentity identity = pendingIdentity();
        EmailVerificationCode challenge = verificationCode(identity.getId(), Instant.now().minusSeconds(1));
        when(identityRepository.findByNormalizedEmailForUpdate(EMAIL)).thenReturn(Optional.of(identity));
        when(verificationRepository.findAllActiveByIdentityIdForUpdate(identity.getId()))
                .thenReturn(List.of(challenge));

        assertThat(verificationService.verify(EMAIL, CODE, CLIENT_IP)).isFalse();

        assertThat(challenge.getConsumedAt()).isNotNull();
        verify(verificationRepository).save(challenge);
        verify(identityRepository, never()).save(any(UserIdentity.class));
    }

    @Test
    void missingIdentityDoesNotLookUpVerificationChallenges() {
        when(identityRepository.findByNormalizedEmailForUpdate(EMAIL)).thenReturn(Optional.empty());

        assertThat(verificationService.verify(EMAIL, CODE, CLIENT_IP)).isFalse();

        verify(verificationRepository, never()).findAllActiveByIdentityIdForUpdate(any());
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
        verify(verificationRepository, never()).findAllActiveByIdentityIdForUpdate(any());
    }

    private static UserIdentity pendingIdentity() {
        return UserIdentity.builder()
                .id(UUID.randomUUID())
                .email(EMAIL)
                .normalizedEmail(EMAIL)
                .passwordHash("encoded-password")
                .status(IdentityStatus.PENDING_EMAIL)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private static EmailVerificationCode verificationCode(UUID identityId, Instant expiresAt) {
        return EmailVerificationCode.builder()
                .id(UUID.randomUUID())
                .normalizedEmail(EMAIL)
                .identityId(identityId)
                .codeHash(VerificationCodeHasher.hash(CODE))
                .createdAt(Instant.now().minusSeconds(10))
                .expiresAt(expiresAt)
                .build();
    }
}

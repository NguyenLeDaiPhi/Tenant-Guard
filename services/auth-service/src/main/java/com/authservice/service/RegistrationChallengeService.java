package com.authservice.service;

import com.authservice.domain.EmailAddress;
import com.authservice.domain.IdentityStatus;
import com.authservice.persistence.EmailVerificationChallenge;
import com.authservice.persistence.EmailVerificationChallengeRepository;
import com.authservice.persistence.UserIdentity;
import com.authservice.persistence.UserIdentityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RegistrationChallengeService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserIdentityRepository identityRepository;
    private final EmailVerificationChallengeRepository verificationRepository;
    private final PasswordEncoder passwordEncoder;
    private final RegistrationEmailLock registrationEmailLock;
    private final Duration verificationTtl;

    public RegistrationChallengeService(
            UserIdentityRepository identityRepository,
            EmailVerificationChallengeRepository verificationRepository,
            PasswordEncoder passwordEncoder,
            RegistrationEmailLock registrationEmailLock,
            @Value("${auth.email.verification-ttl:PT15M}") Duration verificationTtl) {
        this.identityRepository = identityRepository;
        this.verificationRepository = verificationRepository;
        this.passwordEncoder = passwordEncoder;
        this.registrationEmailLock = registrationEmailLock;
        this.verificationTtl = verificationTtl;
    }

    @Transactional
    public Optional<VerificationChallenge> prepare(
            EmailAddress emailAddress,
            String rawPassword) {
        String normalizedEmail = emailAddress.normalized();
        registrationEmailLock.acquire(normalizedEmail);

        UserIdentity identity = identityRepository.findByNormalizedEmailForUpdate(normalizedEmail)
                .orElse(null);
        if (identity != null && identity.isVerified()) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        String passwordHash = passwordEncoder.encode(rawPassword);
        if (identity == null) {
            identity = UserIdentity.builder()
                    .id(UUID.randomUUID())
                    .email(emailAddress.raw())
                    .normalizedEmail(normalizedEmail)
                    .passwordHash(passwordHash) 
                    .status(IdentityStatus.PENDING_EMAIL)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
        } else {
            identity.setEmail(emailAddress.raw());
            identity.setPasswordHash(passwordHash);
        }
        identity = identityRepository.saveAndFlush(identity);

        for (EmailVerificationChallenge existing : verificationRepository
                .findAllPendingByNormalizedEmailForUpdate(normalizedEmail)) {
            verificationRepository.delete(existing);
        }
        verificationRepository.flush();

        String code = generateCode();
        verificationRepository.save(EmailVerificationChallenge.builder()
                .id(UUID.randomUUID())
                .identity(identity)
                .codeHash(VerificationCodeHasher.hash(code))
                .createdAt(now)
                .expiresAt(now.plus(verificationTtl))
                .failedAttempts(0)
                .build());

        return Optional.of(new VerificationChallenge(emailAddress.raw(), code));
    }

    private static String generateCode() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public record VerificationChallenge(String recipientEmail, String code) {
    }
}

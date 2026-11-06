package com.authservice.service;

import com.authservice.domain.AccountStatus;
import com.authservice.domain.EmailAddress;
import com.authservice.domain.IdentityStatus;
import com.authservice.persistence.EmailVerificationCode;
import com.authservice.persistence.EmailVerificationCodeRepository;
import com.authservice.persistence.UserAccount;
import com.authservice.persistence.UserAccountRepository;
import com.authservice.persistence.UserIdentity;
import com.authservice.persistence.UserIdentityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
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
    private final EmailVerificationCodeRepository verificationRepository;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final Duration verificationTtl;

    public RegistrationChallengeService(
            UserIdentityRepository identityRepository,
            EmailVerificationCodeRepository verificationRepository,
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbcTemplate,
            @Value("${auth.email.verification-ttl:PT15M}") Duration verificationTtl) {
        this.identityRepository = identityRepository;
        this.verificationRepository = verificationRepository;
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
        this.verificationTtl = verificationTtl;
    }

    @Transactional
    public Optional<VerificationChallenge> prepare(
            EmailAddress emailAddress,
            String rawPassword) {
        String normalizedEmail = emailAddress.normalized();
        acquireRegistrationLock(normalizedEmail);

        UserIdentity identity = identityRepository.findByNormalizedEmailForUpdate(normalizedEmail)
                .orElse(null);
        if (identity != null && identity.isVerified()) {
            return Optional.empty();
        }

        List<UserAccount> existingAccounts = userAccountRepository.findAllByNormalizedEmail(normalizedEmail);
        if (existingAccounts.stream().anyMatch(account -> account.getStatus() != AccountStatus.PENDING_EMAIL)) {
            return Optional.empty();
        }

        if (identity == null) {
            Instant now = Instant.now();
            identity = UserIdentity.builder()
                    .id(UUID.randomUUID())
                    .email(emailAddress.raw())
                    .normalizedEmail(normalizedEmail)
                    .status(IdentityStatus.PENDING_EMAIL)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
        }
        identity.setEmail(emailAddress.raw());
        identity.setPasswordHash(passwordEncoder.encode(rawPassword));
        identity.setStatus(IdentityStatus.PENDING_EMAIL);
        identity.setEmailVerifiedAt(null);
        identity = identityRepository.save(identity);
        identityRepository.flush();

        Instant now = Instant.now();
        for (EmailVerificationCode existing : verificationRepository
                .findAllActiveByNormalizedEmailForUpdate(normalizedEmail)) {
            existing.consume(now);
            verificationRepository.save(existing);
        }

        String code = generateCode();
        verificationRepository.save(EmailVerificationCode.builder()
                .id(UUID.randomUUID())
                .normalizedEmail(normalizedEmail)
                .identityId(identity.getId())
                .codeHash(VerificationCodeHasher.hash(code))
                .createdAt(now)
                .expiresAt(now.plus(verificationTtl))
                .failedAttempts(0)
                .build());

        return Optional.of(new VerificationChallenge(emailAddress.raw(), code));
    }

    private void acquireRegistrationLock(String normalizedEmail) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtext(?))")) {
                statement.setString(1, "auth-registration:" + normalizedEmail);
                statement.execute();
            }
            return null;
        });
    }

    private static String generateCode() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public record VerificationChallenge(String recipientEmail, String code) {
    }
}

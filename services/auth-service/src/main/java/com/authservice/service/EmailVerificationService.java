package com.authservice.service;

import com.authservice.domain.EmailAddress;
import com.authservice.domain.IdentityStatus;
import com.authservice.persistence.EmailVerificationCode;
import com.authservice.persistence.EmailVerificationCodeRepository;
import com.authservice.persistence.UserIdentity;
import com.authservice.persistence.UserIdentityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final Pattern CODE_FORMAT = Pattern.compile("[0-9a-fA-F]{64}");

    private final EmailVerificationCodeRepository verificationRepository;
    private final UserIdentityRepository identityRepository;
    private final RegistrationRateLimiter rateLimiter;

    public EmailVerificationService(
            EmailVerificationCodeRepository verificationRepository,
            UserIdentityRepository identityRepository,
            RegistrationRateLimiter rateLimiter) {
        this.verificationRepository = verificationRepository;
        this.identityRepository = identityRepository;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public boolean verify(String rawEmail, String code, String clientIp) {
        String normalizedEmail = EmailAddress.normalize(rawEmail);
        log.info("Processing email verification request.");
        rateLimiter.checkVerification(clientIp, normalizedEmail);

        if (code == null || !CODE_FORMAT.matcher(code).matches()) {
            return false;
        }

        UserIdentity identity = identityRepository.findByNormalizedEmailForUpdate(normalizedEmail)
                .orElse(null);
        if (identity == null || identity.isVerified()) {
            log.info("Email verification did not match a pending identity.");
            return false;
        }

        List<EmailVerificationCode> codes = verificationRepository
                .findAllActiveByIdentityIdForUpdate(identity.getId());
        if (codes.isEmpty()) {
            log.info("Email verification did not match an active challenge.");
            return false;
        }

        Instant now = Instant.now();
        EmailVerificationCode verificationCode = codes.getFirst();
        if (!verificationCode.isUsable(now)) {
            verificationCode.consume(now);
            verificationRepository.save(verificationCode);
            log.info("Email verification challenge was expired or exhausted.");
            return false;
        }

        if (!VerificationCodeHasher.matches(verificationCode.getCodeHash(), code)) {
            verificationCode.recordFailedAttempt(now);
            verificationRepository.save(verificationCode);
            log.info("Email verification challenge did not match.");
            return false;
        }

        identity.setStatus(IdentityStatus.VERIFIED);
        identity.setEmailVerifiedAt(now);
        verificationCode.consume(now);
        identityRepository.save(identity);
        verificationRepository.save(verificationCode);
        log.info("Email ownership verified; no tenant membership was activated.");
        return true;
    }

    @Scheduled(fixedDelay = 21_600_000)
    @Transactional
    public void deleteExpiredChallenges() {
        int deleted = verificationRepository.deleteExpiredOrConsumedBefore(
                Instant.now().minusSeconds(30L * 24 * 60 * 60));
        if (deleted > 0) {
            log.info("Removed {} expired or consumed verification challenge(s).", deleted);
        }
    }
}

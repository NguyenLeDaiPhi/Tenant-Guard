package com.authservice.service;

import com.authservice.domain.EmailAddress;
import com.authservice.domain.IdentityStatus;
import com.authservice.persistence.EmailVerificationChallenge;
import com.authservice.persistence.EmailVerificationChallengeRepository;
import com.authservice.persistence.UserIdentity;
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

    private final EmailVerificationChallengeRepository verificationRepository;
    private final RegistrationRateLimiter rateLimiter;
    private final RegistrationEmailLock registrationEmailLock;

    public EmailVerificationService(
            EmailVerificationChallengeRepository verificationRepository,
            RegistrationRateLimiter rateLimiter,
            RegistrationEmailLock registrationEmailLock) {
        this.verificationRepository = verificationRepository;
        this.rateLimiter = rateLimiter;
        this.registrationEmailLock = registrationEmailLock;
    }

    @Transactional
    public boolean verify(String rawEmail, String code, String clientIp) {
        String normalizedEmail = EmailAddress.normalize(rawEmail);
        log.info("Processing email verification request.");
        rateLimiter.checkVerification(clientIp, normalizedEmail);

        if (code == null || !CODE_FORMAT.matcher(code).matches()) {
            return false;
        }

        registrationEmailLock.acquire(normalizedEmail);
        Instant now = Instant.now();
        List<EmailVerificationChallenge> codes = verificationRepository
                .findAllPendingByNormalizedEmailForUpdate(normalizedEmail);
        if (codes.isEmpty()) {
            log.info("Email verification did not match an active challenge.");
            return false;
        }
        EmailVerificationChallenge verificationCode = codes.getFirst();
        if (!verificationCode.isUsable(now)) {
            verificationRepository.delete(verificationCode);
            log.info("Email verification challenge was expired or exhausted and removed.");
            return false;
        }

        UserIdentity identity = verificationCode.getIdentity();
        if (identity.getStatus() != IdentityStatus.PENDING_EMAIL) {
            verificationRepository.delete(verificationCode);
            log.info("Email verification challenge was removed for an already verified identity.");
            return false;
        }

        if (!VerificationCodeHasher.matches(verificationCode.getCodeHash(), code)) {
            verificationCode.recordFailedAttempt(now);
            if (verificationCode.getConsumedAt() != null) {
                verificationRepository.delete(verificationCode);
            } else {
                verificationRepository.save(verificationCode);
            }
            log.info("Email verification challenge did not match.");
            return false;
        }

        identity.setEmail(EmailAddress.of(rawEmail).raw());
        identity.verify(now);
        verificationRepository.delete(verificationCode);
        log.info("Email ownership verified and temporary registration data removed.");
        return true;
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void deleteExpiredChallenges() {
        Instant cutoff = Instant.now();
        int deleted = verificationRepository.deleteExpiredOrConsumedBefore(cutoff);
        if (deleted > 0) {
            log.info("Removed {} expired or consumed verification challenge(s).", deleted);
        }
    }
}

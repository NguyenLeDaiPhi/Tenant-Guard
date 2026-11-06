package com.authservice.service;

import com.authservice.domain.EmailAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final RegistrationChallengeService challengeService;
    private final VerificationEmailSender emailSender;
    private final RegistrationRateLimiter rateLimiter;

    public RegistrationService(
            RegistrationChallengeService challengeService,
            VerificationEmailSender emailSender,
            RegistrationRateLimiter rateLimiter) {
        this.challengeService = challengeService;
        this.emailSender = emailSender;
        this.rateLimiter = rateLimiter;
    }

    public void register(String rawEmail, String rawPassword, String clientIp) {
        PasswordPolicy.validate(rawPassword);

        EmailAddress emailAddress = EmailAddress.of(rawEmail);
        String normalizedEmail = emailAddress.normalized();
        log.info("Processing self-service registration request.");

        rateLimiter.checkRegistration(clientIp, normalizedEmail);

        Optional<RegistrationChallengeService.VerificationChallenge> challenge =
                challengeService.prepare(emailAddress, rawPassword);
        challenge.ifPresent(value -> emailSender.sendVerificationCode(
                value.recipientEmail(),
                value.code()));
        log.info("Self-service registration request processed.");
    }
}

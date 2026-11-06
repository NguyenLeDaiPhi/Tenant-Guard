package com.authservice.service;

import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RegistrationRateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);
    private final RateLimitService rateLimitService;

    public RegistrationRateLimiter(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    public void checkRegistration(String clientIp, String normalizedEmail) {
        enforce("register:ip", clientIp, 5);
        enforce("register:email", normalizedEmail, 5);
    }

    public void checkVerification(String clientIp, String normalizedEmail) {
        enforce("verify:ip", clientIp, 10);
        enforce("verify:email", normalizedEmail, 10);
    }

    private void enforce(String scope, String subject, int maximum) {
        if (rateLimitService.consume(scope, subject, WINDOW) > maximum) {
            throw new RateLimitExceededException();
        }
    }
}

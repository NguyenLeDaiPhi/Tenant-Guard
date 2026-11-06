package com.authservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class SmtpVerificationEmailSender implements VerificationEmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpVerificationEmailSender.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final Duration verificationTtl;

    public SmtpVerificationEmailSender(
            JavaMailSender mailSender,
            @Value("${auth.email.from:no-reply@tenantguard.local}") String fromAddress,
            @Value("${auth.email.verification-ttl:PT15M}") Duration verificationTtl) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.verificationTtl = verificationTtl;
    }

    @Override
    public void sendVerificationCode(String email, String code) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Recipient email must not be blank");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Verification code must not be blank");
        }

        log.info("Preparing verification email.");

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(email);
            message.setSubject("Verify your TenantGuard account");
            message.setText("""
                    Your TenantGuard email verification code is:

                    %s

                    Submit this code to verify your email address.
                    This code expires in %d minutes. If you did not request this, you can ignore this message.
                    """.formatted(code, verificationTtl.toMinutes()));

            mailSender.send(message);
            log.info("Verification email successfully dispatched.");
        } catch (MailException ex) {
            log.error("Failed to dispatch verification email (exception type: {}).",
                    ex.getClass().getSimpleName());
            throw new EmailDeliveryException("Failed to send verification email.", ex);
        }
    }
}

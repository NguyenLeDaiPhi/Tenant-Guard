package com.authservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpVerificationEmailSenderTests {

    private static final String FROM_ADDRESS = "no-reply@tenantguard.local";
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final String RECIPIENT = "user@example.com";
    private static final String RAW_CODE = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";

    private JavaMailSender mailSender;
    private SmtpVerificationEmailSender emailSender;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        emailSender = new SmtpVerificationEmailSender(mailSender, FROM_ADDRESS, TTL);
    }

    @Test
    void sendsVerificationCodeWithCorrectHeadersAndBody() {
        emailSender.sendVerificationCode(RECIPIENT, RAW_CODE);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        SimpleMailMessage message = captor.getValue();
        assertThat(message.getFrom()).isEqualTo(FROM_ADDRESS);
        assertThat(message.getTo()).containsExactly(RECIPIENT);
        assertThat(message.getSubject()).isEqualTo("Verify your TenantGuard account");
        assertThat(message.getText()).contains(RAW_CODE);
        assertThat(message.getText()).contains("15 minutes");
    }

    @Test
    void throwsEmailDeliveryExceptionWhenMailSenderFails() {
        doThrow(new MailSendException("SMTP connection refused"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> emailSender.sendVerificationCode(RECIPIENT, RAW_CODE))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageContaining(RECIPIENT)
                .hasCauseInstanceOf(MailSendException.class);
    }

    @Test
    void rejectsBlankEmailOrCode() {
        assertThatThrownBy(() -> emailSender.sendVerificationCode("", RAW_CODE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> emailSender.sendVerificationCode(null, RAW_CODE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> emailSender.sendVerificationCode(RECIPIENT, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> emailSender.sendVerificationCode(RECIPIENT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}


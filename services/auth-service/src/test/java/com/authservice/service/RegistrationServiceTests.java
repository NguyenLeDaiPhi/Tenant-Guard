package com.authservice.service;

import com.authservice.domain.EmailAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationServiceTests {

    private static final String EMAIL = "member@example.com";
    private static final String PASSWORD = "SecurePassword123!";
    private static final String CLIENT_IP = "192.0.2.1";
    private static final String CODE = "a".repeat(64);

    private RegistrationChallengeService challengeService;
    private VerificationEmailSender emailSender;
    private RegistrationRateLimiter rateLimiter;
    private RegistrationService registrationService;

    @BeforeEach
    void setUp() {
        challengeService = mock(RegistrationChallengeService.class);
        emailSender = mock(VerificationEmailSender.class);
        rateLimiter = mock(RegistrationRateLimiter.class);
        registrationService = new RegistrationService(challengeService, emailSender, rateLimiter);
    }

    @Test
    void preparesChallengeForNormalizedEmailAndSendsItsVerificationCode() {
        String rawEmail = "Member@Example.com";
        RegistrationChallengeService.VerificationChallenge challenge =
                new RegistrationChallengeService.VerificationChallenge(rawEmail, CODE);
        when(challengeService.prepare(any(EmailAddress.class), eq(PASSWORD)))
                .thenReturn(Optional.of(challenge));

        registrationService.register("  " + rawEmail + "  ", PASSWORD, CLIENT_IP);

        var emailCaptor = org.mockito.ArgumentCaptor.forClass(EmailAddress.class);
        verify(challengeService).prepare(emailCaptor.capture(), eq(PASSWORD));
        assertThat(emailCaptor.getValue().raw()).isEqualTo(rawEmail);
        assertThat(emailCaptor.getValue().normalized()).isEqualTo(EMAIL);
        verify(rateLimiter).checkRegistration(CLIENT_IP, EMAIL);
        verify(emailSender).sendVerificationCode(rawEmail, CODE);
    }

    @Test
    void doesNotSendEmailWhenChallengePreparationDeclinesRegistration() {
        when(challengeService.prepare(any(EmailAddress.class), eq(PASSWORD)))
                .thenReturn(Optional.empty());

        registrationService.register(EMAIL, PASSWORD, CLIENT_IP);

        verify(challengeService).prepare(any(EmailAddress.class), eq(PASSWORD));
        verify(emailSender, never()).sendVerificationCode(any(), any());
    }

    @Test
    void propagatesEmailDeliveryFailureAfterPreparingChallenge() {
        RegistrationChallengeService.VerificationChallenge challenge =
                new RegistrationChallengeService.VerificationChallenge(EMAIL, CODE);
        when(challengeService.prepare(any(EmailAddress.class), eq(PASSWORD)))
                .thenReturn(Optional.of(challenge));
        doThrow(new EmailDeliveryException("SMTP connection timed out"))
                .when(emailSender).sendVerificationCode(EMAIL, CODE);

        assertThatThrownBy(() -> registrationService.register(EMAIL, PASSWORD, CLIENT_IP))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageContaining("SMTP connection timed out");

        verify(challengeService).prepare(any(EmailAddress.class), eq(PASSWORD));
    }

    @Test
    void rateLimitedRegistrationDoesNotPrepareOrSendChallenge() {
        doThrow(new RateLimitExceededException())
                .when(rateLimiter).checkRegistration(CLIENT_IP, EMAIL);

        assertThatThrownBy(() -> registrationService.register(EMAIL, PASSWORD, CLIENT_IP))
                .isInstanceOf(RateLimitExceededException.class);

        verify(challengeService, never()).prepare(any(EmailAddress.class), any());
        verify(emailSender, never()).sendVerificationCode(any(), any());
    }

    @Test
    void rejectsInvalidEmailAndPasswordsOutsidePolicyLimits() {
        assertThatThrownBy(() -> registrationService.register("not-an-email", PASSWORD, CLIENT_IP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registrationService.register(EMAIL, "short", CLIENT_IP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least");
        assertThatThrownBy(() -> registrationService.register(EMAIL, "é".repeat(37), CLIENT_IP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("72 bytes");

        verify(rateLimiter, never()).checkRegistration(any(), any());
        verify(challengeService, never()).prepare(any(EmailAddress.class), any());
    }
}

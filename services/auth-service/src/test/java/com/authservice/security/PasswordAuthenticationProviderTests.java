package com.authservice.security;

import com.authservice.domain.IdentityStatus;
import com.authservice.persistence.UserIdentity;
import com.authservice.persistence.UserIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordAuthenticationProviderTests {

    private UserIdentityRepository userIdentityRepository;
    private PasswordEncoder passwordEncoder;
    private PasswordAuthenticationProvider provider;

    private final String rawPassword = "ValidPassword123!";
    private final String encodedPassword = new BCryptPasswordEncoder().encode(rawPassword);

    @BeforeEach
    void setUp() {
        userIdentityRepository = mock(UserIdentityRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        provider = new PasswordAuthenticationProvider(userIdentityRepository, passwordEncoder);
    }

    @Test
    void acceptsVerifiedGlobalIdentityWithoutTenantMembership() {
        UUID userId = UUID.randomUUID();
        String email = "user@example.com";
        UserIdentity identity = verifiedIdentity(userId, email);
        when(userIdentityRepository.findByNormalizedEmailForUpdate(email)).thenReturn(Optional.of(identity));

        Authentication result = provider.authenticate(new PasswordAuthenticationToken(email, rawPassword));

        assertThat(result).isNotNull();
        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getPrincipal()).isEqualTo(new GlobalUserPrincipal(userId, email));
        verify(userIdentityRepository).save(identity);
    }

    @Test
    void rejectsIncorrectPasswordAndRecordsFailureOnGlobalIdentity() {
        String email = "user@example.com";
        UserIdentity identity = verifiedIdentity(UUID.randomUUID(), email);
        identity.setFailedLoginCount(2);
        when(userIdentityRepository.findByNormalizedEmailForUpdate(email)).thenReturn(Optional.of(identity));

        assertInvalidCredentials(email, "WrongPassword!");

        ArgumentCaptor<UserIdentity> captor = ArgumentCaptor.forClass(UserIdentity.class);
        verify(userIdentityRepository).save(captor.capture());
        assertThat(captor.getValue().getFailedLoginCount()).isEqualTo(3);
        assertThat(captor.getValue().getStatus()).isEqualTo(IdentityStatus.VERIFIED);
    }

    @Test
    void locksGlobalIdentityAfterTenIncorrectPasswords() {
        String email = "user@example.com";
        UserIdentity identity = verifiedIdentity(UUID.randomUUID(), email);
        identity.setFailedLoginCount(9);
        when(userIdentityRepository.findByNormalizedEmailForUpdate(email)).thenReturn(Optional.of(identity));

        assertInvalidCredentials(email, "WrongPassword!");

        assertThat(identity.getFailedLoginCount()).isEqualTo(10);
        assertThat(identity.getStatus()).isEqualTo(IdentityStatus.LOCKED);
        assertThat(identity.getLockedUntil()).isAfter(Instant.now());
        verify(userIdentityRepository).save(identity);
    }

    @Test
    void rejectsUnverifiedDisabledAndLockedGlobalIdentities() {
        String email = "user@example.com";
        UserIdentity pending = UserIdentity.builder()
                .id(UUID.randomUUID())
                .email(email)
                .normalizedEmail(email)
                .passwordHash(encodedPassword)
                .status(IdentityStatus.PENDING_EMAIL)
                .build();
        when(userIdentityRepository.findByNormalizedEmailForUpdate(email)).thenReturn(Optional.of(pending));
        assertInvalidCredentials(email, rawPassword);

        UserIdentity disabled = verifiedIdentity(UUID.randomUUID(), email);
        disabled.setStatus(IdentityStatus.DISABLED);
        when(userIdentityRepository.findByNormalizedEmailForUpdate(email)).thenReturn(Optional.of(disabled));
        assertInvalidCredentials(email, rawPassword);

        UserIdentity locked = verifiedIdentity(UUID.randomUUID(), email);
        locked.setStatus(IdentityStatus.LOCKED);
        locked.setLockedUntil(Instant.now().plusSeconds(600));
        when(userIdentityRepository.findByNormalizedEmailForUpdate(email)).thenReturn(Optional.of(locked));
        assertInvalidCredentials(email, rawPassword);

        verify(userIdentityRepository, never()).save(any(UserIdentity.class));
    }

    @Test
    void successfulLoginResetsExpiredLockoutState() {
        String email = "user@example.com";
        UserIdentity identity = verifiedIdentity(UUID.randomUUID(), email);
        identity.setStatus(IdentityStatus.LOCKED);
        identity.setFailedLoginCount(10);
        identity.setLockedUntil(Instant.now().minusSeconds(1));
        when(userIdentityRepository.findByNormalizedEmailForUpdate(email)).thenReturn(Optional.of(identity));

        Authentication result = provider.authenticate(new PasswordAuthenticationToken(email, rawPassword));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(identity.getStatus()).isEqualTo(IdentityStatus.VERIFIED);
        assertThat(identity.getFailedLoginCount()).isZero();
        assertThat(identity.getLockedUntil()).isNull();
        assertThat(identity.getLastLoginAt()).isNotNull();
        verify(userIdentityRepository).save(identity);
    }

    @Test
    void rejectsUnknownIdentityWithGenericError() {
        String email = "missing@example.com";
        when(userIdentityRepository.findByNormalizedEmailForUpdate(email)).thenReturn(Optional.empty());

        assertInvalidCredentials(email, rawPassword);
    }

    private UserIdentity verifiedIdentity(UUID id, String email) {
        return UserIdentity.builder()
                .id(id)
                .email(email)
                .normalizedEmail(email)
                .passwordHash(encodedPassword)
                .status(IdentityStatus.VERIFIED)
                .emailVerifiedAt(Instant.now())
                .build();
    }

    private void assertInvalidCredentials(String email, String password) {
        assertThatThrownBy(() -> provider.authenticate(new PasswordAuthenticationToken(email, password)))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials or account unavailable");
    }
}

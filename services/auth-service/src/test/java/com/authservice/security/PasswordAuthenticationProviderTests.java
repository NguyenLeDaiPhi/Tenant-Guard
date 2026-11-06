package com.authservice.security;

import com.authservice.domain.AccountStatus;
import com.authservice.domain.UserRole;
import com.authservice.persistence.UserAccount;
import com.authservice.persistence.UserAccountRepository;
import com.authservice.service.TenantStatusService;
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

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private UserAccountRepository userAccountRepository;
    private TenantStatusService tenantStatusService;
    private PasswordEncoder passwordEncoder;
    private PasswordAuthenticationProvider provider;

    private final String rawPassword = "ValidPassword123!";
    private final String encodedPassword = new BCryptPasswordEncoder().encode(rawPassword);

    @BeforeEach
    void setUp() {
        userAccountRepository = mock(UserAccountRepository.class);
        tenantStatusService = mock(TenantStatusService.class);
        passwordEncoder = new BCryptPasswordEncoder();
        provider = new PasswordAuthenticationProvider(userAccountRepository, tenantStatusService, passwordEncoder);
    }

    @Test
    void rejectsTenantWhenTenantIsInactive() {
        UUID tenantId = TENANT_ID;
        when(tenantStatusService.isTenantActive(tenantId)).thenReturn(false);

        PasswordAuthenticationToken token = new PasswordAuthenticationToken(tenantId, "user@example.com", rawPassword);

        assertThatThrownBy(() -> provider.authenticate(token))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials or account unavailable");

        verify(userAccountRepository, never()).findByTenantIdAndNormalizedEmail(any(), any());
    }

    @Test
    void locksAccountAfterTenIncorrectPasswords() {
        UUID tenantId = TENANT_ID;
        String email = "user@example.com";
        when(tenantStatusService.isTenantActive(tenantId)).thenReturn(true);

        UserAccount user = UserAccount.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(email)
                .normalizedEmail(email)
                .passwordHash(encodedPassword)
                .role(UserRole.ORDER_VIEWER)
                .status(AccountStatus.ACTIVE)
                .failedLoginCount(9) // 9th failure previously
                .build();

        when(userAccountRepository.findByTenantIdAndNormalizedEmail(eq(tenantId), eq(email)))
                .thenReturn(Optional.of(user));

        PasswordAuthenticationToken token = new PasswordAuthenticationToken(tenantId, email, "WrongPassword!");

        assertThatThrownBy(() -> provider.authenticate(token))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials or account unavailable");

        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(captor.capture());
        UserAccount savedUser = captor.getValue();
        assertThat(savedUser.getFailedLoginCount()).isEqualTo(10);
        assertThat(savedUser.getStatus()).isEqualTo(AccountStatus.LOCKED);
        assertThat(savedUser.getLockedUntil()).isNotNull();
    }

    @Test
    void rejectsIncorrectPasswordAndRecordsFailure() {
        UUID tenantId = TENANT_ID;
        String email = "user@example.com";
        when(tenantStatusService.isTenantActive(tenantId)).thenReturn(true);

        UserAccount user = UserAccount.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(email)
                .normalizedEmail(email)
                .passwordHash(encodedPassword)
                .role(UserRole.ORDER_VIEWER)
                .status(AccountStatus.ACTIVE)
                .failedLoginCount(2)
                .build();

        when(userAccountRepository.findByTenantIdAndNormalizedEmail(eq(tenantId), eq(email)))
                .thenReturn(Optional.of(user));

        PasswordAuthenticationToken token = new PasswordAuthenticationToken(tenantId, email, "WrongPassword!");

        assertThatThrownBy(() -> provider.authenticate(token))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials or account unavailable");

        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(captor.capture());
        UserAccount savedUser = captor.getValue();
        assertThat(savedUser.getFailedLoginCount()).isEqualTo(3);
        assertThat(savedUser.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void acceptsValidPasswordForActiveAccountAndTenant() {
        UUID tenantId = TENANT_ID;
        String email = "user@example.com";
        when(tenantStatusService.isTenantActive(tenantId)).thenReturn(true);

        UserAccount user = UserAccount.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(email)
                .normalizedEmail(email)
                .passwordHash(encodedPassword)
                .role(UserRole.ORDER_VIEWER)
                .status(AccountStatus.ACTIVE)
                .failedLoginCount(3)
                .build();

        when(userAccountRepository.findByTenantIdAndNormalizedEmail(eq(tenantId), eq(email)))
                .thenReturn(Optional.of(user));

        PasswordAuthenticationToken token = new PasswordAuthenticationToken(tenantId, email, rawPassword);
        Authentication result = provider.authenticate(token);

        assertThat(result).isNotNull();
        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getPrincipal()).isInstanceOf(UserAccountPrincipal.class);
        UserAccountPrincipal principal = (UserAccountPrincipal) result.getPrincipal();
        assertThat(principal.getUsername()).isEqualTo(email);
        assertThat(principal.getRole()).isEqualTo(UserRole.ORDER_VIEWER);

        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(captor.capture());
        UserAccount savedUser = captor.getValue();
        assertThat(savedUser.getFailedLoginCount()).isEqualTo(0);
        assertThat(savedUser.getLastLoginAt()).isNotNull();
    }

    @Test
    void rejectsDisabledAndLockedAccountsEvenWhenPasswordIsCorrect() {
        UUID tenantId = TENANT_ID;
        String email = "user@example.com";
        when(tenantStatusService.isTenantActive(tenantId)).thenReturn(true);

        // Case 1: DISABLED account
        UserAccount disabledUser = UserAccount.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(email)
                .normalizedEmail(email)
                .passwordHash(encodedPassword)
                .role(UserRole.ORDER_VIEWER)
                .status(AccountStatus.DISABLED)
                .build();

        when(userAccountRepository.findByTenantIdAndNormalizedEmail(eq(tenantId), eq(email)))
                .thenReturn(Optional.of(disabledUser));

        PasswordAuthenticationToken token1 = new PasswordAuthenticationToken(tenantId, email, rawPassword);
        assertThatThrownBy(() -> provider.authenticate(token1))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials or account unavailable");

        // Case 2: LOCKED account (lock not expired)
        UserAccount lockedUser = UserAccount.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(email)
                .normalizedEmail(email)
                .passwordHash(encodedPassword)
                .role(UserRole.ORDER_VIEWER)
                .status(AccountStatus.LOCKED)
                .lockedUntil(Instant.now().plusSeconds(600))
                .build();

        when(userAccountRepository.findByTenantIdAndNormalizedEmail(eq(tenantId), eq(email)))
                .thenReturn(Optional.of(lockedUser));

        PasswordAuthenticationToken token2 = new PasswordAuthenticationToken(tenantId, email, rawPassword);
        assertThatThrownBy(() -> provider.authenticate(token2))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials or account unavailable");
    }
}

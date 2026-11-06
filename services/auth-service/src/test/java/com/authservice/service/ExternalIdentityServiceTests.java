package com.authservice.service;

import com.authservice.domain.AccountStatus;
import com.authservice.domain.AuthenticatedIdentity;
import com.authservice.domain.UserRole;
import com.authservice.persistence.ExternalIdentity;
import com.authservice.persistence.ExternalIdentityRepository;
import com.authservice.persistence.UserAccount;
import com.authservice.persistence.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalIdentityServiceTests {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private ExternalIdentityRepository externalIdentityRepository;
    private UserAccountRepository userAccountRepository;
    private TenantStatusService tenantStatusService;
    private ExternalIdentityService externalIdentityService;

    @BeforeEach
    void setUp() {
        externalIdentityRepository = mock(ExternalIdentityRepository.class);
        userAccountRepository = mock(UserAccountRepository.class);
        tenantStatusService = mock(TenantStatusService.class);
        externalIdentityService = new ExternalIdentityService(
                externalIdentityRepository,
                userAccountRepository,
                tenantStatusService
        );
    }

    @Test
    void refusesToLinkIdentityAlreadyOwnedByDifferentAccount() {
        UUID existingUserId = UUID.randomUUID();
        UUID newUserId = UUID.randomUUID();
        String subject = "google-subject-12345";

        UserAccount newUser = UserAccount.builder()
                .id(newUserId)
                .tenantId(TENANT_ID)
                .email("user2@example.com")
                .normalizedEmail("user2@example.com")
                .role(UserRole.ORDER_VIEWER)
                .status(AccountStatus.ACTIVE)
                .build();

        ExternalIdentity existingIdentity = ExternalIdentity.builder()
                .id(UUID.randomUUID())
                .userId(existingUserId)
                .provider(ExternalIdentityService.PROVIDER_GOOGLE)
                .providerSubject(subject)
                .emailVerified(true)
                .build();

        when(userAccountRepository.findById(newUserId)).thenReturn(Optional.of(newUser));
        when(externalIdentityRepository.findByProviderAndProviderSubject(ExternalIdentityService.PROVIDER_GOOGLE, subject))
                .thenReturn(Optional.of(existingIdentity));

        assertThatThrownBy(() -> externalIdentityService.linkIdentity(
                newUserId, ExternalIdentityService.PROVIDER_GOOGLE, subject, true))
                .isInstanceOf(ExternalIdentityConflictException.class)
                .hasMessageContaining("already linked to a different account");

        verify(externalIdentityRepository, never()).save(any());
    }

    @Test
    void linkingSameSubjectToSameAccountIsIdempotent() {
        UUID userId = UUID.randomUUID();
        String subject = "google-subject-12345";

        UserAccount user = UserAccount.builder()
                .id(userId)
                .tenantId(TENANT_ID)
                .email("user@example.com")
                .normalizedEmail("user@example.com")
                .role(UserRole.ORDER_VIEWER)
                .status(AccountStatus.ACTIVE)
                .build();

        ExternalIdentity existingIdentity = ExternalIdentity.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .provider(ExternalIdentityService.PROVIDER_GOOGLE)
                .providerSubject(subject)
                .emailVerified(true)
                .build();

        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(externalIdentityRepository.findByProviderAndProviderSubject(ExternalIdentityService.PROVIDER_GOOGLE, subject))
                .thenReturn(Optional.of(existingIdentity));

        ExternalIdentity result = externalIdentityService.linkIdentity(
                userId, ExternalIdentityService.PROVIDER_GOOGLE, subject, true);

        assertThat(result).isSameAs(existingIdentity);
        verify(externalIdentityRepository, never()).save(any());
    }

    @Test
    void authenticatesOnlyFromProviderSubjectMapping() {
        UUID userId = UUID.randomUUID();
        String subject = "google-subject-12345";

        ExternalIdentity identity = ExternalIdentity.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .provider(ExternalIdentityService.PROVIDER_GOOGLE)
                .providerSubject(subject)
                .emailVerified(true)
                .build();

        UserAccount user = UserAccount.builder()
                .id(userId)
                .tenantId(TENANT_ID)
                .email("user@example.com")
                .normalizedEmail("user@example.com")
                .role(UserRole.ORDER_VIEWER)
                .status(AccountStatus.ACTIVE)
                .build();

        when(externalIdentityRepository.findByProviderAndProviderSubject(ExternalIdentityService.PROVIDER_GOOGLE, subject))
                .thenReturn(Optional.of(identity));
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));

        AuthenticatedIdentity authenticated = externalIdentityService.authenticateGoogleIdentity(subject);

        assertThat(authenticated.userId()).isEqualTo(userId);
        assertThat(authenticated.tenantId()).isEqualTo(TENANT_ID);
        assertThat(authenticated.role()).isEqualTo(UserRole.ORDER_VIEWER);
        assertThat(authenticated.email()).isEqualTo("user@example.com");
    }

    @Test
    void matchingEmailDoesNotLinkAnUnmappedGoogleSubject() {
        String unmappedSubject = "unmapped-google-sub-999";

        when(externalIdentityRepository.findByProviderAndProviderSubject(ExternalIdentityService.PROVIDER_GOOGLE, unmappedSubject))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> externalIdentityService.authenticateGoogleIdentity(unmappedSubject))
                .isInstanceOf(ExternalIdentityNotLinkedException.class)
                .hasMessageContaining("Google identity is not linked");

        // Asserts that no account lookup or automatic linking occurs solely by email
        verify(userAccountRepository, never()).findByNormalizedEmailAndTenantIdIsNull(any());
        verify(userAccountRepository, never()).findByTenantIdAndNormalizedEmail(any(), any());
        verify(externalIdentityRepository, never()).save(any());
    }
}

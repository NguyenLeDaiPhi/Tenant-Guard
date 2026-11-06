package com.authservice.service;

import com.authservice.domain.IdentityStatus;
import com.authservice.domain.MembershipStatus;
import com.authservice.domain.UserRole;
import com.authservice.persistence.TenantMembership;
import com.authservice.persistence.TenantMembershipRepository;
import com.authservice.persistence.UserAccount;
import com.authservice.persistence.UserAccountRepository;
import com.authservice.persistence.UserIdentity;
import com.authservice.persistence.UserIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MembershipServiceTests {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String EMAIL = "user@example.com";

    private TenantMembershipRepository membershipRepository;
    private UserAccountRepository userAccountRepository;
    private UserIdentityRepository identityRepository;
    private TenantStatusService tenantStatusService;
    private JdbcTemplate jdbcTemplate;
    private MembershipService membershipService;

    @BeforeEach
    void setUp() {
        membershipRepository = mock(TenantMembershipRepository.class);
        userAccountRepository = mock(UserAccountRepository.class);
        identityRepository = mock(UserIdentityRepository.class);
        tenantStatusService = mock(TenantStatusService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        membershipService = new MembershipService(
                membershipRepository,
                userAccountRepository,
                identityRepository,
                tenantStatusService,
                jdbcTemplate
        );
    }

    @Test
    void tenantAdministratorCanProvisionPendingMembershipForUnverifiedIdentity() {
        UUID creatorId = UUID.randomUUID();
        when(identityRepository.findByNormalizedEmailForUpdate(EMAIL)).thenReturn(Optional.empty());
        when(membershipRepository.findByTenantIdAndNormalizedEmailForUpdate(TENANT_ID, EMAIL))
                .thenReturn(Optional.empty());
        when(userAccountRepository.existsByTenantIdAndNormalizedEmail(TENANT_ID, EMAIL)).thenReturn(false);
        when(membershipRepository.save(any(TenantMembership.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TenantMembership membership = membershipService.createPendingMembership(
                creatorId,
                UserRole.TENANT_ADMIN,
                TENANT_ID,
                TENANT_ID,
                "User@Example.com",
                UserRole.ORDER_MANAGER
        );

        assertThat(membership.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(membership.getNormalizedEmail()).isEqualTo(EMAIL);
        assertThat(membership.getRole()).isEqualTo(UserRole.ORDER_MANAGER);
        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.PENDING);
        assertThat(membership.getCreatedBy()).isEqualTo(creatorId);
        assertThat(membership.getUserId()).isNull();
        verify(tenantStatusService).validateTenantActive(TENANT_ID);
        verify(membershipRepository).save(membership);
        verify(userAccountRepository, never()).save(any(UserAccount.class));
    }

    @Test
    void verifiedIdentityReceivesActiveAccountAndMembership() {
        UUID creatorId = UUID.randomUUID();
        UserIdentity identity = verifiedIdentity();
        when(identityRepository.findByNormalizedEmailForUpdate(EMAIL)).thenReturn(Optional.of(identity));
        when(membershipRepository.findByTenantIdAndNormalizedEmailForUpdate(TENANT_ID, EMAIL))
                .thenReturn(Optional.empty());
        when(userAccountRepository.existsByTenantIdAndNormalizedEmail(TENANT_ID, EMAIL)).thenReturn(false);
        when(userAccountRepository.save(any(UserAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(membershipRepository.save(any(TenantMembership.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TenantMembership membership = membershipService.createPendingMembership(
                creatorId,
                UserRole.TENANT_ADMIN,
                TENANT_ID,
                TENANT_ID,
                EMAIL,
                UserRole.ORDER_VIEWER
        );

        assertThat(membership.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(membership.getActivatedAt()).isNotNull();
        assertThat(membership.getUserId()).isNotNull();

        var accountCaptor = org.mockito.ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(accountCaptor.capture());
        UserAccount account = accountCaptor.getValue();
        assertThat(account.getIdentityId()).isEqualTo(identity.getId());
        assertThat(account.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(account.getNormalizedEmail()).isEqualTo(EMAIL);
        assertThat(account.getPasswordHash()).isEqualTo(identity.getPasswordHash());
        assertThat(account.getRole()).isEqualTo(UserRole.ORDER_VIEWER);
        assertThat(membership.getUserId()).isEqualTo(account.getId());
        verify(membershipRepository).save(membership);
    }

    @Test
    void rejectsNonTenantAdminsAndCrossTenantMembershipProvisioning() {
        UUID otherTenant = UUID.randomUUID();

        assertThatThrownBy(() -> membershipService.createPendingMembership(
                UUID.randomUUID(),
                UserRole.PLATFORM_ADMIN,
                null,
                TENANT_ID,
                EMAIL,
                UserRole.ORDER_VIEWER
        )).isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> membershipService.createPendingMembership(
                UUID.randomUUID(),
                UserRole.TENANT_ADMIN,
                TENANT_ID,
                otherTenant,
                EMAIL,
                UserRole.ORDER_VIEWER
        )).isInstanceOf(AccessDeniedException.class);

        verify(membershipRepository, never()).save(any(TenantMembership.class));
        verify(identityRepository, never()).findByNormalizedEmailForUpdate(any());
    }

    @Test
    void rejectsExistingMembershipWithDifferentRoleAndPlatformRoleAssignments() {
        TenantMembership existingMembership = TenantMembership.builder()
                .id(UUID.randomUUID())
                .tenantId(TENANT_ID)
                .normalizedEmail(EMAIL)
                .role(UserRole.ORDER_MANAGER)
                .status(MembershipStatus.PENDING)
                .createdBy(UUID.randomUUID())
                .createdAt(Instant.now())
                .build();
        when(identityRepository.findByNormalizedEmailForUpdate(EMAIL)).thenReturn(Optional.empty());
        when(membershipRepository.findByTenantIdAndNormalizedEmailForUpdate(TENANT_ID, EMAIL))
                .thenReturn(Optional.of(existingMembership));

        assertThatThrownBy(() -> membershipService.createPendingMembership(
                UUID.randomUUID(),
                UserRole.TENANT_ADMIN,
                TENANT_ID,
                TENANT_ID,
                EMAIL,
                UserRole.ORDER_VIEWER
        )).isInstanceOf(AccountAlreadyExistsException.class);

        assertThatThrownBy(() -> membershipService.createPendingMembership(
                UUID.randomUUID(),
                UserRole.TENANT_ADMIN,
                TENANT_ID,
                TENANT_ID,
                "other@example.com",
                UserRole.PLATFORM_ADMIN
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static UserIdentity verifiedIdentity() {
        return UserIdentity.builder()
                .id(UUID.randomUUID())
                .email(EMAIL)
                .normalizedEmail(EMAIL)
                .passwordHash("identity-password-hash")
                .status(IdentityStatus.VERIFIED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .emailVerifiedAt(Instant.now())
                .build();
    }
}

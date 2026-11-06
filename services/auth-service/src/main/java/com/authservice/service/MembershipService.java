package com.authservice.service;

import com.authservice.domain.EmailAddress;
import com.authservice.domain.AccountStatus;
import com.authservice.domain.MembershipStatus;
import com.authservice.domain.UserRole;
import com.authservice.persistence.TenantMembership;
import com.authservice.persistence.TenantMembershipRepository;
import com.authservice.persistence.UserAccount;
import com.authservice.persistence.UserAccountRepository;
import com.authservice.persistence.UserIdentity;
import com.authservice.persistence.UserIdentityRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class MembershipService {

    private final TenantMembershipRepository membershipRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserIdentityRepository identityRepository;
    private final TenantStatusService tenantStatusService;
    private final JdbcTemplate jdbcTemplate;

    public MembershipService(
            TenantMembershipRepository membershipRepository,
            UserAccountRepository userAccountRepository,
            UserIdentityRepository identityRepository,
            TenantStatusService tenantStatusService,
            JdbcTemplate jdbcTemplate) {
        this.membershipRepository = membershipRepository;
        this.userAccountRepository = userAccountRepository;
        this.identityRepository = identityRepository;
        this.tenantStatusService = tenantStatusService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public TenantMembership createPendingMembership(
            UUID creatorId,
            UserRole creatorRole,
            UUID creatorTenantId,
            UUID targetTenantId,
            String email,
            UserRole assignedRole) {

        if (creatorRole != UserRole.TENANT_ADMIN
                || creatorTenantId == null
                || !creatorTenantId.equals(targetTenantId)) {
            throw new AccessDeniedException("Only a tenant administrator can provision membership in their tenant");
        }
        if (assignedRole == null || !assignedRole.isTenantRole()) {
            throw new IllegalArgumentException("A tenant role is required");
        }

        EmailAddress emailAddress = EmailAddress.of(email);
        String normalizedEmail = emailAddress.normalized();
        tenantStatusService.validateTenantActive(targetTenantId);
        acquireProvisioningLock(targetTenantId, normalizedEmail);

        Optional<UserIdentity> identity = identityRepository.findByNormalizedEmailForUpdate(normalizedEmail);
        Optional<TenantMembership> existingMembership =
                membershipRepository.findByTenantIdAndNormalizedEmailForUpdate(targetTenantId, normalizedEmail);

        if (existingMembership.isPresent()) {
            TenantMembership membership = existingMembership.get();
            if (membership.getRole() != assignedRole) {
                throw new AccountAlreadyExistsException(
                        "A membership already exists for this email with a different role");
            }
            if (membership.getStatus() == MembershipStatus.ACTIVE) {
                return membership;
            }
            if (identity.filter(UserIdentity::isVerified).isPresent()) {
                return activateMembership(membership, identity.get(), emailAddress.raw());
            }
            return membership;
        }

        if (identity.filter(UserIdentity::isVerified).isPresent()) {
            if (userAccountRepository.existsByTenantIdAndNormalizedEmail(targetTenantId, normalizedEmail)) {
                throw new AccountAlreadyExistsException("An account already exists for this email");
            }

            UserIdentity verifiedIdentity = identity.get();
            Instant now = Instant.now();
            UserAccount account = createActiveAccount(
                    targetTenantId, emailAddress.raw(), normalizedEmail, assignedRole, verifiedIdentity, now);
            userAccountRepository.save(account);
            return membershipRepository.save(TenantMembership.builder()
                    .id(UUID.randomUUID())
                    .tenantId(targetTenantId)
                    .normalizedEmail(normalizedEmail)
                    .role(assignedRole)
                    .status(MembershipStatus.ACTIVE)
                    .userId(account.getId())
                    .createdBy(creatorId)
                    .createdAt(now)
                    .activatedAt(now)
                    .build());
        }

        if (userAccountRepository.existsByTenantIdAndNormalizedEmail(targetTenantId, normalizedEmail)) {
            throw new AccountAlreadyExistsException("An account already exists for this email");
        }

        return membershipRepository.save(TenantMembership.builder()
                .id(UUID.randomUUID())
                .tenantId(targetTenantId)
                .normalizedEmail(normalizedEmail)
                .role(assignedRole)
                .status(MembershipStatus.PENDING)
                .createdBy(creatorId)
                .createdAt(Instant.now())
                .build());
    }

    private TenantMembership activateMembership(
            TenantMembership membership,
            UserIdentity identity,
            String email) {
        Instant now = Instant.now();
        UserAccount account;
        if (membership.getUserId() == null) {
            account = userAccountRepository
                    .findByTenantIdAndNormalizedEmail(membership.getTenantId(), membership.getNormalizedEmail())
                    .orElseGet(() -> createActiveAccount(
                            membership.getTenantId(),
                            email,
                            membership.getNormalizedEmail(),
                            membership.getRole(),
                            identity,
                            now));
        } else {
            account = userAccountRepository.findById(membership.getUserId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Pending membership references a missing user account"));
        }

        if (account.getRole() != membership.getRole()) {
            throw new AccountAlreadyExistsException(
                    "The existing account has a different role from the pending membership");
        }
        if (account.getStatus() != AccountStatus.PENDING_EMAIL
                && !(account.getStatus() == AccountStatus.ACTIVE
                && identity.getId().equals(account.getIdentityId()))) {
            throw new AccountAlreadyExistsException(
                    "The existing account is not eligible for identity activation");
        }
        account.setIdentityId(identity.getId());
        account.setEmail(email);
        account.setPasswordHash(identity.getPasswordHash());
        if (account.getStatus() == AccountStatus.PENDING_EMAIL) {
            account.setStatus(AccountStatus.ACTIVE);
            account.setFailedLoginCount(0);
            account.setLockedUntil(null);
        }
        userAccountRepository.save(account);

        membership.setUserId(account.getId());
        membership.activate(now);
        return membershipRepository.save(membership);
    }

    private static UserAccount createActiveAccount(
            UUID tenantId,
            String email,
            String normalizedEmail,
            UserRole role,
            UserIdentity identity,
            Instant now) {
        return UserAccount.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .identityId(identity.getId())
                .email(email)
                .normalizedEmail(normalizedEmail)
                .passwordHash(identity.getPasswordHash())
                .role(role)
                .status(AccountStatus.ACTIVE)
                .failedLoginCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private void acquireProvisioningLock(UUID tenantId, String normalizedEmail) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtext(?))")) {
                statement.setString(1, "auth-membership:" + tenantId + ":" + normalizedEmail);
                statement.execute();
            }
            return null;
        });
    }
}

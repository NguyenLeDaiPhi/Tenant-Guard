package com.tenant.service.membership.service;

import com.tenant.service.common.exception.ConflictException;
import com.tenant.service.common.exception.ResourceNotFoundException;
import com.tenant.service.membership.dto.InvitationResponse;
import com.tenant.service.membership.dto.MembershipResponse;
import com.tenant.service.membership.entity.InvitationStatus;
import com.tenant.service.membership.entity.MembershipRole;
import com.tenant.service.membership.entity.MembershipStatus;
import com.tenant.service.membership.entity.TenantInvitation;
import com.tenant.service.membership.entity.TenantMembership;
import com.tenant.service.membership.repository.TenantInvitationRepository;
import com.tenant.service.membership.repository.TenantMembershipRepository;
import com.tenant.service.security.TenantAuthorizationService;
import com.tenant.service.tenant.entity.Tenant;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class TenantInvitationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Duration INVITATION_TTL = Duration.ofDays(7);

    private final TenantInvitationRepository invitationRepository;
    private final TenantMembershipRepository membershipRepository;
    private final TenantAuthorizationService authorizationService;
    private final TenantInvitationEmailSender emailSender;
    private final TransactionTemplate transactionTemplate;

    public TenantInvitationService(
            TenantInvitationRepository invitationRepository,
            TenantMembershipRepository membershipRepository,
            TenantAuthorizationService authorizationService,
            TenantInvitationEmailSender emailSender,
            PlatformTransactionManager transactionManager) {
        this.invitationRepository = invitationRepository;
        this.membershipRepository = membershipRepository;
        this.authorizationService = authorizationService;
        this.emailSender = emailSender;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public InvitationResponse create(
            UUID tenantId,
            UUID requesterId,
            String rawEmail,
            MembershipRole role) {
        String normalizedEmail = normalizeEmail(rawEmail);
        CreatedInvitation created = transactionTemplate.execute(status -> {
            Tenant tenant = authorizationService.requireCanInvite(tenantId, requesterId, role);
            Instant now = Instant.now();
            invitationRepository.findAllForUpdate(tenantId, normalizedEmail, InvitationStatus.PENDING)
                    .forEach(existing -> {
                        existing.setStatus(InvitationStatus.REVOKED);
                        invitationRepository.save(existing);
                    });

            String token = generateToken();
            Instant expiresAt = now.plus(INVITATION_TTL);
            TenantInvitation invitation = invitationRepository.save(TenantInvitation.builder()
                    .id(UUID.randomUUID())
                    .tenantId(tenantId)
                    .normalizedEmail(normalizedEmail)
                    .role(role)
                    .tokenHash(hashToken(token))
                    .status(InvitationStatus.PENDING)
                    .invitedBy(requesterId)
                    .createdAt(now)
                    .expiresAt(expiresAt)
                    .build());
            return new CreatedInvitation(
                    response(invitation),
                    new TenantInvitationEmailSender.InvitationEmail(
                            normalizedEmail,
                            tenant.getName(),
                            role.name(),
                            token,
                            expiresAt));
        });

        if (created == null) {
            throw new IllegalStateException("Invitation transaction completed without a result");
        }
        emailSender.send(created.email());
        return created.response();
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> list(UUID tenantId, UUID requesterId) {
        authorizationService.requireMemberManager(tenantId, requesterId);
        return invitationRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream()
                .map(TenantInvitationService::response)
                .toList();
    }

    @Transactional
    public void revoke(UUID tenantId, UUID invitationId, UUID requesterId) {
        authorizationService.requireMemberManager(tenantId, requesterId);
        TenantInvitation invitation = invitationRepository.findByIdAndTenantId(invitationId, tenantId)
                .orElseThrow(ResourceNotFoundException::new);
        if (invitation.getStatus() == InvitationStatus.PENDING) {
            invitation.setStatus(InvitationStatus.REVOKED);
            invitationRepository.save(invitation);
        }
    }

    @Transactional
    public MembershipResponse accept(String rawToken, UUID userId, String email) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Invitation token must not be blank");
        }
        String normalizedEmail = normalizeEmail(email);
        TenantInvitation invitation = invitationRepository
                .findByTokenHashForUpdate(hashToken(rawToken), InvitationStatus.PENDING)
                .orElseThrow(ResourceNotFoundException::new);
        Instant now = Instant.now();
        if (invitation.getExpiresAt() == null
                || !invitation.getExpiresAt().isAfter(now)
                || !invitation.getNormalizedEmail().equals(normalizedEmail)) {
            throw new ResourceNotFoundException();
        }
        authorizationService.requireActiveTenant(invitation.getTenantId());

        TenantMembership membership = membershipRepository
                .findByTenantIdAndUserId(invitation.getTenantId(), userId)
                .map(existing -> reactivateOrReject(existing, invitation.getRole(), now))
                .orElseGet(() -> newMembership(invitation, userId, now));
        membership = membershipRepository.save(membership);

        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setAcceptedUserId(userId);
        invitation.setAcceptedAt(now);
        invitationRepository.save(invitation);
        return response(membership);
    }

    private static TenantMembership reactivateOrReject(
            TenantMembership membership,
            MembershipRole invitedRole,
            Instant now) {
        if (membership.getStatus() == MembershipStatus.ACTIVE) {
            throw new ConflictException("The user is already an active tenant member.");
        }
        if (membership.getStatus() != MembershipStatus.REMOVED) {
            throw new AccessDeniedException("The existing membership cannot be reactivated by invitation.");
        }
        membership.setRole(invitedRole);
        membership.setStatus(MembershipStatus.ACTIVE);
        membership.setOwner(false);
        membership.setJoinedAt(now);
        membership.setUpdatedAt(now);
        return membership;
    }

    private static TenantMembership newMembership(TenantInvitation invitation, UUID userId, Instant now) {
        return TenantMembership.builder()
                .id(UUID.randomUUID())
                .tenantId(invitation.getTenantId())
                .userId(userId)
                .role(invitation.getRole())
                .status(MembershipStatus.ACTIVE)
                .owner(false)
                .joinedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email must not be blank");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static InvitationResponse response(TenantInvitation invitation) {
        return new InvitationResponse(
                invitation.getId(),
                invitation.getTenantId(),
                invitation.getNormalizedEmail(),
                invitation.getRole(),
                invitation.getStatus(),
                invitation.getCreatedAt(),
                invitation.getExpiresAt());
    }

    private static MembershipResponse response(TenantMembership membership) {
        return new MembershipResponse(
                membership.getId(),
                membership.getTenantId(),
                membership.getUserId(),
                membership.getRole(),
                membership.getStatus(),
                membership.isOwner(),
                membership.getJoinedAt(),
                membership.getCreatedAt());
    }

    private record CreatedInvitation(
            InvitationResponse response,
            TenantInvitationEmailSender.InvitationEmail email) {
    }
}

package com.tenant.service.security;

import com.tenant.service.common.exception.ResourceNotFoundException;
import com.tenant.service.membership.entity.MembershipRole;
import com.tenant.service.membership.entity.MembershipStatus;
import com.tenant.service.membership.repository.TenantMembershipRepository;
import com.tenant.service.tenant.entity.Tenant;
import com.tenant.service.tenant.entity.TenantStatus;
import com.tenant.service.tenant.repository.TenantRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TenantAuthorizationService {

    private final TenantMembershipRepository membershipRepository;
    private final TenantRepository tenantRepository;

    public TenantAuthorizationService(
            TenantMembershipRepository membershipRepository,
            TenantRepository tenantRepository) {
        this.membershipRepository = membershipRepository;
        this.tenantRepository = tenantRepository;
    }

    public Tenant requireActiveMember(UUID tenantId, UUID userId) {
        Tenant tenant = requireActiveTenant(tenantId);
        if (!membershipRepository.existsByTenantIdAndUserIdAndStatus(
                tenantId, userId, MembershipStatus.ACTIVE)) {
            throw new ResourceNotFoundException();
        }
        return tenant;
    }

    public Tenant requireActiveTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .filter(tenant -> tenant.getStatus() == TenantStatus.ACTIVE)
                .orElseThrow(ResourceNotFoundException::new);
    }

    public Tenant requireOwner(UUID tenantId, UUID userId) {
        Tenant tenant = requireActiveMember(tenantId, userId);
        boolean owner = membershipRepository.findByTenantIdAndUserIdAndStatus(
                        tenantId, userId, MembershipStatus.ACTIVE)
                .map(membership -> membership.getRole() == MembershipRole.OWNER)
                .orElse(false);
        if (!owner) {
            throw new AccessDeniedException("Tenant OWNER membership is required");
        }
        return tenant;
    }

    public Tenant requireMemberManager(UUID tenantId, UUID userId) {
        Tenant tenant = requireActiveMember(tenantId, userId);
        MembershipRole role = membershipRepository.findByTenantIdAndUserIdAndStatus(
                        tenantId, userId, MembershipStatus.ACTIVE)
                .map(membership -> membership.getRole())
                .orElseThrow(ResourceNotFoundException::new);
        if (role != MembershipRole.OWNER && role != MembershipRole.ADMIN) {
            throw new AccessDeniedException("Tenant OWNER or ADMIN membership is required");
        }
        return tenant;
    }   

    public Tenant requireCanInvite(
            UUID tenantId,
            UUID userId,
            MembershipRole invitedRole) {
        Tenant tenant = requireMemberManager(tenantId, userId);
        MembershipRole requesterRole = membershipRepository.findByTenantIdAndUserIdAndStatus(
                        tenantId, userId, MembershipStatus.ACTIVE)
                .map(membership -> membership.getRole())
                .orElseThrow(ResourceNotFoundException::new);
        if (invitedRole == null
                || invitedRole == MembershipRole.OWNER
                || (requesterRole == MembershipRole.ADMIN && invitedRole != MembershipRole.MEMBER)) {
            throw new AccessDeniedException("The caller cannot invite the requested membership role");
        }
        return tenant;
    }
}

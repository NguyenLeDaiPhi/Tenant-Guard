package com.tenant.service.membership.service;

import com.tenant.service.membership.dto.MembershipResponse;
import com.tenant.service.membership.entity.MembershipStatus;
import com.tenant.service.membership.repository.TenantMembershipRepository;
import com.tenant.service.security.TenantAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class MembershipService {

    private final TenantMembershipRepository membershipRepository;
    private final TenantAuthorizationService authorizationService;

    public MembershipService(
            TenantMembershipRepository membershipRepository,
            TenantAuthorizationService authorizationService) {
        this.membershipRepository = membershipRepository;
        this.authorizationService = authorizationService;
    }

    @Transactional(readOnly = true)
    public List<MembershipResponse> listActiveMemberships(UUID userId) {
        return membershipRepository
                .findAllByUserIdAndStatusOrderByJoinedAtDesc(userId, MembershipStatus.ACTIVE)
                .stream()
                .map(membership -> new MembershipResponse(
                        membership.getId(),
                        membership.getTenantId(),
                        membership.getUserId(),
                        membership.getRole(),
                        membership.getStatus(),
                        membership.isOwner(),
                        membership.getJoinedAt(),
                        membership.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MembershipResponse> listTenantMemberships(UUID tenantId, UUID requesterId) {
        authorizationService.requireMemberManager(tenantId, requesterId);
        return membershipRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream()
                .map(membership -> new MembershipResponse(
                        membership.getId(),
                        membership.getTenantId(),
                        membership.getUserId(),
                        membership.getRole(),
                        membership.getStatus(),
                        membership.isOwner(),
                        membership.getJoinedAt(),
                        membership.getCreatedAt()))
                .toList();
    }
}

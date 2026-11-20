package com.tenant.service.membership.repository;

import com.tenant.service.membership.entity.MembershipStatus;
import com.tenant.service.membership.entity.TenantMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantMembershipRepository extends JpaRepository<TenantMembership, UUID> {

    List<TenantMembership> findAllByUserIdAndStatusOrderByJoinedAtDesc(UUID userId, MembershipStatus status);

    Optional<TenantMembership> findByTenantIdAndUserIdAndStatus(
            UUID tenantId,
            UUID userId,
            MembershipStatus status);

    Optional<TenantMembership> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    List<TenantMembership> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    boolean existsByTenantIdAndUserIdAndStatus(UUID tenantId, UUID userId, MembershipStatus status);
}

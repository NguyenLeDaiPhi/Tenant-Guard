package com.tenant.service.membership.repository;

import com.tenant.service.membership.entity.InvitationStatus;
import com.tenant.service.membership.entity.TenantInvitation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantInvitationRepository extends JpaRepository<TenantInvitation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select invitation from TenantInvitation invitation
            where invitation.tenantId = :tenantId
              and invitation.normalizedEmail = :normalizedEmail
              and invitation.status = :status
            """)
    List<TenantInvitation> findAllForUpdate(
            @Param("tenantId") UUID tenantId,
            @Param("normalizedEmail") String normalizedEmail,
            @Param("status") InvitationStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select invitation from TenantInvitation invitation
            where invitation.tokenHash = :tokenHash
              and invitation.status = :status
            """)
    Optional<TenantInvitation> findByTokenHashForUpdate(
            @Param("tokenHash") String tokenHash,
            @Param("status") InvitationStatus status);

    Optional<TenantInvitation> findByIdAndTenantId(UUID id, UUID tenantId);

    List<TenantInvitation> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}

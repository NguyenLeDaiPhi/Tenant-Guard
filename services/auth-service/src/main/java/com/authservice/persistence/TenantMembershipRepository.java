package com.authservice.persistence;

import com.authservice.domain.MembershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantMembershipRepository extends JpaRepository<TenantMembership, UUID> {

    boolean existsByTenantIdAndNormalizedEmail(UUID tenantId, String normalizedEmail);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select membership from TenantMembership membership
        where membership.tenantId = :tenantId
          and membership.normalizedEmail = :normalizedEmail
        """)
    Optional<TenantMembership> findByTenantIdAndNormalizedEmailForUpdate(
            @Param("tenantId") UUID tenantId,
            @Param("normalizedEmail") String normalizedEmail);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select membership from TenantMembership membership
        where membership.normalizedEmail = :normalizedEmail
          and membership.status = :status
        order by membership.createdAt
        """)
    List<TenantMembership> findAllByNormalizedEmailAndStatusForUpdate(
            @Param("normalizedEmail") String normalizedEmail,
            @Param("status") MembershipStatus status);
}

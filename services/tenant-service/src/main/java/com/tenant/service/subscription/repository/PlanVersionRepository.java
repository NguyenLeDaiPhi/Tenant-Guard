package com.tenant.service.subscription.repository;

import com.tenant.service.subscription.entity.PlanPublicationStatus;
import com.tenant.service.subscription.entity.PlanVersion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanVersionRepository extends JpaRepository<PlanVersion, UUID> {

    Optional<PlanVersion> findFirstByPlanIdAndPublicationStatusAndEffectiveAtLessThanEqualOrderByVersionDesc(
            UUID planId,
            PlanPublicationStatus status,
            Instant effectiveAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select version from PlanVersion version
            where version.planId = :planId
              and version.publicationStatus = :status
              and version.effectiveAt <= :effectiveAt
            order by version.version desc
            """)
    List<PlanVersion> findCurrentPublishedVersionsForUpdate(
            @Param("planId") UUID planId,
            @Param("status") PlanPublicationStatus status,
            @Param("effectiveAt") Instant effectiveAt,
            org.springframework.data.domain.Pageable pageable);
}

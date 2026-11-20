package com.tenant.service.subscription.repository;

import com.tenant.service.subscription.entity.PlanEntitlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanEntitlementRepository extends JpaRepository<PlanEntitlement, UUID> {

    List<PlanEntitlement> findAllByPlanVersionIdOrderByEntitlementKeyAsc(UUID planVersionId);
}

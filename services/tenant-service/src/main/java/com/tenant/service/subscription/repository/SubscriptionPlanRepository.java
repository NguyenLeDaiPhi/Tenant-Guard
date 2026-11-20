package com.tenant.service.subscription.repository;

import com.tenant.service.subscription.entity.SubscriptionPlan;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, UUID> {

    List<SubscriptionPlan> findAllByActiveTrueOrderByCodeAsc();

    Optional<SubscriptionPlan> findByCodeAndActiveTrue(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select plan from SubscriptionPlan plan
            where plan.code = :code and plan.active = true
            """)
    Optional<SubscriptionPlan> findActiveByCodeForUpdate(@Param("code") String code);
}

package com.tenant.service.subscription.repository;

import com.tenant.service.subscription.entity.SubscriptionEntitlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SubscriptionEntitlementRepository extends JpaRepository<SubscriptionEntitlement, UUID> {

    List<SubscriptionEntitlement> findAllBySubscriptionIdOrderByEntitlementKeyAsc(UUID subscriptionId);
}

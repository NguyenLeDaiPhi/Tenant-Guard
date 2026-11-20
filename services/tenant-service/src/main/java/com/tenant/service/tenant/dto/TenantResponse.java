package com.tenant.service.tenant.dto;

import com.tenant.service.membership.entity.MembershipRole;
import com.tenant.service.tenant.entity.TenantStatus;
import com.tenant.service.subscription.dto.EntitlementResponse;
import com.tenant.service.subscription.entity.TenantSubscriptionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TenantResponse(
        UUID id,
        String name,
        String slug,
        UUID ownerUserId,
        TenantStatus status,
        Instant createdAt,
        MembershipRole currentUserRole,
        SubscriptionResponse subscription) {

    public record SubscriptionResponse(
            TenantSubscriptionStatus status,
            String planCode,
            int planVersion,
            Instant selectedAt,
            Instant startedAt,
            List<EntitlementResponse> entitlements) {
    }
}

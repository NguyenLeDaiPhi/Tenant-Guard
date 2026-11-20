package com.tenant.service.subscription.dto;

import com.tenant.service.subscription.entity.BillingInterval;

import java.util.List;

public record SubscriptionPlanResponse(
        String code,
        String name,
        String description,
        int version,
        Long priceMinor,
        String currency,
        BillingInterval billingInterval,
        List<EntitlementResponse> entitlements) {
}

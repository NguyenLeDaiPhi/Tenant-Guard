package com.tenant.service.subscription.dto;

import com.tenant.service.subscription.entity.EntitlementType;

public record EntitlementResponse(
    String key, 
    EntitlementType type, 
    Object value, 
    String unit) {
}

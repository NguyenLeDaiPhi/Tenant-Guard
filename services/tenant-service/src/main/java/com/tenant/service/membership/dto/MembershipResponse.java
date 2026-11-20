package com.tenant.service.membership.dto;

import com.tenant.service.membership.entity.MembershipRole;
import com.tenant.service.membership.entity.MembershipStatus;

import java.time.Instant;
import java.util.UUID;

public record MembershipResponse(
        UUID id,
        UUID tenantId,
        UUID userId,
        MembershipRole role,
        MembershipStatus status,
        boolean owner,
        Instant joinedAt,
        Instant createdAt) {
}

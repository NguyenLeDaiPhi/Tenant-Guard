package com.tenant.service.membership.dto;

import com.tenant.service.membership.entity.InvitationStatus;
import com.tenant.service.membership.entity.MembershipRole;

import java.time.Instant;
import java.util.UUID;

public record InvitationResponse(
        UUID id,
        UUID tenantId,
        String email,
        MembershipRole role,
        InvitationStatus status,
        Instant createdAt,
        Instant expiresAt) {
}

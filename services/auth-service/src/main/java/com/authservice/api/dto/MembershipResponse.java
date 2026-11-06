package com.authservice.api.dto;

import com.authservice.domain.MembershipStatus;
import com.authservice.domain.UserRole;

import java.util.UUID;

public record MembershipResponse(
        UUID membershipId,
        UUID tenantId,
        String email,
        UserRole role,
        MembershipStatus status
) {}

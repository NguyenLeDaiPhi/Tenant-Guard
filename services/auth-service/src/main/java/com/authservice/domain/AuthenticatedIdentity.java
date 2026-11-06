package com.authservice.domain;

import java.util.Objects;
import java.util.UUID;

public record AuthenticatedIdentity(
        UUID userId,
        UUID tenantId,
        UserRole role,
        String email
) {
    public AuthenticatedIdentity {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(email, "email must not be null");
        if (role == UserRole.PLATFORM_ADMIN && tenantId != null) {
            throw new IllegalArgumentException("Platform admin must not have a tenant ID");
        }
        if (role != UserRole.PLATFORM_ADMIN && tenantId == null) {
            throw new IllegalArgumentException("Tenant users must have a tenant ID");
        }
    }
}

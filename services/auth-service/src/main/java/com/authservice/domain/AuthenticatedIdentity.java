package com.authservice.domain;

import java.util.UUID;

public record AuthenticatedIdentity(
        UUID globalUserId,
        String email
) {
    public AuthenticatedIdentity {
        if (globalUserId == null) {
            throw new IllegalArgumentException("Global user ID must not be null");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email must not be blank");
        }
    }
}

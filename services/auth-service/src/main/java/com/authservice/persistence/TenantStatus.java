package com.authservice.persistence;

import java.util.UUID;

public record TenantStatus(UUID id, String slug, String status) {
    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}

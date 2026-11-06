package com.authservice.domain;

public enum UserRole {
    PLATFORM_ADMIN,
    TENANT_ADMIN,
    ORDER_MANAGER,
    ORDER_VIEWER;

    public boolean isPlatformRole() {
        return this == PLATFORM_ADMIN;
    }

    public boolean isTenantRole() {
        return this != PLATFORM_ADMIN;
    }
}


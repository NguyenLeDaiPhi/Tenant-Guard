package com.authservice.service;

import com.authservice.persistence.TenantStatus;
import com.authservice.persistence.TenantStatusRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class TenantStatusService {

    private final TenantStatusRepository tenantStatusRepository;

    public TenantStatusService(TenantStatusRepository tenantStatusRepository) {
        this.tenantStatusRepository = tenantStatusRepository;
    }

    public void validateTenantActive(UUID tenantId) {
        if (tenantId == null) {
            return; // Platform admin has no tenant
        }
        TenantStatus tenant = tenantStatusRepository.findById(tenantId)
                .orElseThrow(() -> new TenantUnavailableException("Tenant not found: " + tenantId));
        if (!tenant.isActive()) {
            throw new TenantUnavailableException("Tenant is inactive: " + tenantId);
        }
    }

    public boolean isTenantActive(UUID tenantId) {
        if (tenantId == null) {
            return true;
        }
        return tenantStatusRepository.findById(tenantId)
                .map(TenantStatus::isActive)
                .orElse(false);
    }
}

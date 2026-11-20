package com.tenant.service.tenant.repository;

import com.tenant.service.tenant.entity.Tenant;
import com.tenant.service.tenant.entity.TenantStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findBySlug(String slug);

    Optional<Tenant> findByIdAndStatus(UUID id, TenantStatus status);
}

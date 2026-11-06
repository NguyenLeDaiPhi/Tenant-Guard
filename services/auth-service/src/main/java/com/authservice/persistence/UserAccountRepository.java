package com.authservice.persistence;

import com.authservice.domain.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Repository
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByTenantIdAndNormalizedEmail(UUID tenantId, String normalizedEmail);

    Optional<UserAccount> findByNormalizedEmailAndTenantIdIsNull(String normalizedEmail);

    boolean existsByTenantIdAndNormalizedEmail(UUID tenantId, String normalizedEmail);

    boolean existsByTenantIdAndRole(UUID tenantId, UserRole role);

    List<UserAccount> findAllByNormalizedEmail(String normalizedEmail);

    boolean existsByNormalizedEmailAndTenantIdIsNull(String normalizedEmail);
}

package com.authservice.service;

import com.authservice.domain.AccountStatus;
import com.authservice.domain.EmailAddress;
import com.authservice.domain.UserRole;
import com.authservice.persistence.UserAccount;
import com.authservice.persistence.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "auth.bootstrap.tenant-admin",
        name = "enabled",
        havingValue = "true"
)
public class InitialTenantAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialTenantAdminBootstrap.class);

    private final String tenantIdValue;
    private final String adminEmailValue;
    private final String adminPassword;
    private final TenantStatusService tenantStatusService;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    public InitialTenantAdminBootstrap(
            @Value("${auth.bootstrap.tenant-admin.tenant-id:}") String tenantIdValue,
            @Value("${auth.bootstrap.tenant-admin.email:}") String adminEmailValue,
            @Value("${auth.bootstrap.tenant-admin.password:}") String adminPassword,
            TenantStatusService tenantStatusService,
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbcTemplate) {
        this.tenantIdValue = tenantIdValue;
        this.adminEmailValue = adminEmailValue;
        this.adminPassword = adminPassword;
        this.tenantStatusService = tenantStatusService;
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        UUID tenantId = parseTenantId();
        EmailAddress adminEmail = EmailAddress.of(adminEmailValue);
        PasswordPolicy.validate(adminPassword);
        tenantStatusService.validateTenantActive(tenantId);

        acquireTenantBootstrapLock(tenantId);

        if (userAccountRepository.existsByTenantIdAndRole(tenantId, UserRole.TENANT_ADMIN)) {
            log.info("Initial tenant-admin bootstrap skipped; a tenant administrator already exists.");
            return;
        }

        if (userAccountRepository.existsByTenantIdAndNormalizedEmail(tenantId, adminEmail.normalized())) {
            throw new IllegalStateException(
                    "Initial tenant-admin bootstrap email already belongs to a non-admin account in the tenant");
        }

        Instant now = Instant.now();
        userAccountRepository.save(UserAccount.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(adminEmail.raw())
                .normalizedEmail(adminEmail.normalized())
                .passwordHash(passwordEncoder.encode(adminPassword))
                .role(UserRole.TENANT_ADMIN)
                .status(AccountStatus.ACTIVE)
                .failedLoginCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build());

        log.info("Created the initial tenant administrator for the configured active tenant.");
    }

    private UUID parseTenantId() {
        try {
            return UUID.fromString(tenantIdValue);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "A valid AUTH_BOOTSTRAP_TENANT_ID is required when tenant-admin bootstrap is enabled",
                    exception);
        }
    }

    private void acquireTenantBootstrapLock(UUID tenantId) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtext(?))")) {
                statement.setString(1, "auth-initial-tenant-admin:" + tenantId);
                statement.execute();
            }
            return null;
        });
    }
}

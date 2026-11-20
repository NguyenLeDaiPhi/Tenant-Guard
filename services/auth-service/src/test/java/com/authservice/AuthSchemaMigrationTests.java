package com.authservice;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthSchemaMigrationTests {

    private static final UUID FIRST_ACCOUNT =
            UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID DUPLICATE_ACCOUNT =
            UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID CONFLICTING_ACCOUNT =
            UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final UUID SECOND_CONFLICTING_ACCOUNT =
            UUID.fromString("00000000-0000-4000-8000-000000000004");
    private static final UUID TENANT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_TENANT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID EXTERNAL_LINK_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID UNRESOLVED_EXTERNAL_LINK_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final String SHARED_PASSWORD_HASH = "shared-password-hash";
    private static final String FIRST_CONFLICTING_PASSWORD_HASH = "conflicting-password-hash-1";
    private static final String SECOND_CONFLICTING_PASSWORD_HASH = "conflicting-password-hash-2";
    private static final String PENDING_PASSWORD_HASH = "pending-password-hash";

    @Test
    void upgradesLegacyAuthAndTenantDataWithoutDiscardingUnresolvedRecords() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("tenantguard")
                .withUsername("postgres")
                .withPassword("postgres")) {
            postgres.start();
            createTenantMigratorRole(postgres);

            authFlyway(postgres, MigrationVersion.fromVersion("7")).migrate();
            seedLegacyAuthData(postgres);
            authFlyway(postgres, MigrationVersion.fromVersion("8")).migrate();

            tenantFlyway(postgres, "1").migrate();
            seedTenantMetadata(postgres);
            authFlyway(postgres, MigrationVersion.fromVersion("9")).migrate();

            assertThat(queryLong(postgres, """
                    SELECT COUNT(*) FROM public.user_identities
                    WHERE normalized_email = 'shared@example.com'
                    """)).isEqualTo(1);
            assertThat(queryString(postgres, """
                    SELECT id::text FROM public.user_identities
                    WHERE normalized_email = 'shared@example.com'
                    """)).isEqualTo(FIRST_ACCOUNT.toString());
            assertThat(queryString(postgres, """
                    SELECT password_hash FROM public.user_identities
                    WHERE normalized_email = 'shared@example.com'
                    """)).isEqualTo(SHARED_PASSWORD_HASH);
            assertThat(queryString(postgres, """
                    SELECT global_user_id::text FROM public.external_identities
                    WHERE id = '20000000-0000-4000-8000-000000000001'
                    """)).isEqualTo(FIRST_ACCOUNT.toString());
            assertThat(queryString(postgres, """
                    SELECT password_hash FROM public.user_identities
                    WHERE normalized_email = 'verify-later@example.com'
                    """)).isEqualTo(PENDING_PASSWORD_HASH);
            assertThat(queryBoolean(postgres, """
                    SELECT status = 'PENDING_EMAIL'
                       AND email_verified_at IS NULL
                    FROM public.user_identities
                    WHERE normalized_email = 'verify-later@example.com'
                    """)).isTrue();
            assertThat(queryBoolean(postgres, """
                    SELECT has_table_privilege(
                        'tenant_service_migrator',
                        'public.tenant_memberships',
                        'DELETE')
                    """)).isTrue();
            assertThatThrownBy(() -> authFlyway(postgres, null).migrate())
                    .hasStackTraceContaining(
                            "Tenant Service has not verified and completed the legacy data transfer");
            assertThat(queryLong(postgres, "SELECT COUNT(*) FROM public.tenant_memberships"))
                    .isEqualTo(2);

            tenantFlyway(postgres, "2").migrate();
            tenantFlyway(postgres, "3").migrate();

            assertThat(queryLong(postgres, "SELECT COUNT(*) FROM public.tenant_memberships"))
                    .isZero();
            assertThat(queryLong(postgres, "SELECT COUNT(*) FROM public.registration_invitations"))
                    .isZero();
            assertThat(queryLong(postgres, """
                    SELECT COUNT(*) FROM tenant_service.legacy_auth_memberships
                    WHERE source_membership_id = '30000000-0000-4000-8000-000000000001'
                    """)).isEqualTo(1);
            assertThat(queryString(postgres, """
                    SELECT mapped_user_id::text FROM tenant_service.legacy_auth_memberships
                    WHERE source_membership_id = '30000000-0000-4000-8000-000000000001'
                    """)).isEqualTo(FIRST_ACCOUNT.toString());
            assertThat(queryLong(postgres, """
                    SELECT COUNT(*) FROM tenant_service.legacy_auth_invitations
                    WHERE source_invitation_id = '40000000-0000-4000-8000-000000000001'
                    """)).isEqualTo(1);
            assertThat(queryLong(postgres, """
                    SELECT COUNT(*) FROM tenant_service.legacy_auth_memberships
                    WHERE source_membership_id = '30000000-0000-4000-8000-000000000002'
                      AND mapped_user_id IS NULL
                    """)).isEqualTo(1);
            assertThat(queryBoolean(postgres, """
                    SELECT expected_membership_count = 2
                       AND expected_invitation_count = 1
                       AND transferred_membership_count = 2
                       AND transferred_invitation_count = 1
                       AND completed_at IS NOT NULL
                    FROM auth_service.tenant_data_transfer_status
                    WHERE id = 1
                    """)).isTrue();

            authFlyway(postgres, null).migrate();

            assertThat(queryBoolean(postgres, """
                    SELECT NOT has_table_privilege(
                        'tenant_service_migrator',
                        'auth_service.tenant_data_transfer_status',
                        'SELECT')
                       AND NOT has_schema_privilege(
                        'tenant_service_migrator',
                        'auth_service',
                        'USAGE')
                    """)).isTrue();
            assertThat(queryBoolean(postgres, """
                    SELECT to_regclass('public.users') IS NULL
                       AND to_regclass('public.tenant_memberships') IS NULL
                       AND to_regclass('public.registration_invitations') IS NULL
                    """)).isTrue();
            assertThat(queryLong(postgres, """
                    SELECT COUNT(*) FROM auth_service.legacy_identity_migration_accounts
                    WHERE source_account_id IN (
                        '00000000-0000-4000-8000-000000000003',
                        '00000000-0000-4000-8000-000000000004')
                    """)).isEqualTo(2);
            assertThat(queryString(postgres, """
                    SELECT password_hash FROM auth_service.legacy_identity_migration_accounts
                    WHERE source_account_id = '00000000-0000-4000-8000-000000000003'
                    """)).isEqualTo(FIRST_CONFLICTING_PASSWORD_HASH);
            assertThat(queryLong(postgres, """
                    SELECT COUNT(*) FROM auth_service.legacy_external_identity_mappings
                    WHERE source_external_identity_id = '20000000-0000-4000-8000-000000000002'
                    """)).isEqualTo(1);
            assertThat(queryLong(postgres, """
                    SELECT COUNT(*) FROM public.external_identities
                    WHERE global_user_id IS NULL
                    """)).isZero();
            assertThat(queryLong(postgres, """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'external_identities'
                      AND column_name = 'user_id'
                    """)).isZero();
            assertThat(queryLong(postgres, """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'email_verification_challenges'
                      AND column_name IN ('normalized_email', 'status', 'temporary_password_hash')
                    """)).isZero();
        }
    }

    private static Flyway authFlyway(PostgreSQLContainer<?> postgres, MigrationVersion target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .schemas("auth_service")
                .defaultSchema("auth_service")
                .table("auth_service_flyway_schema_history")
                .target(target == null ? MigrationVersion.LATEST : target)
                .load();
    }

    private static Flyway tenantFlyway(PostgreSQLContainer<?> postgres, String targetVersion) throws Exception {
        Path migrations = Path.of("..", "tenant-service", "src", "main", "resources", "db", "migration")
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(migrations)) {
            throw new IllegalStateException("Tenant migration directory not found: " + migrations);
        }
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + migrations.toString().replace('\\', '/'))
                .schemas("tenant_service")
                .defaultSchema("tenant_service")
                .table("tenant_service_flyway_schema_history")
                .target(targetVersion == null ? MigrationVersion.LATEST
                        : MigrationVersion.fromVersion(targetVersion))
                .load();
    }

    private static void createTenantMigratorRole(PostgreSQLContainer<?> postgres) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE tenant_service_migrator");
            statement.execute("CREATE ROLE tenant_service");
        }
    }

    private static void seedLegacyAuthData(PostgreSQLContainer<?> postgres) throws Exception {
        Instant now = Instant.now();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (PreparedStatement user = connection.prepareStatement("""
                    INSERT INTO public.users
                        (id, tenant_id, email, normalized_email, password_hash, role, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                    """)) {
                insertUser(user, FIRST_ACCOUNT, TENANT_ID, "shared@example.com",
                        SHARED_PASSWORD_HASH, "TENANT_ADMIN", now);
                insertUser(user, DUPLICATE_ACCOUNT, OTHER_TENANT_ID, "shared@example.com",
                        SHARED_PASSWORD_HASH, "ORDER_VIEWER", now);
                insertUser(user, CONFLICTING_ACCOUNT, TENANT_ID, "conflict@example.com",
                        FIRST_CONFLICTING_PASSWORD_HASH, "TENANT_ADMIN", now);
                insertUser(user, SECOND_CONFLICTING_ACCOUNT, OTHER_TENANT_ID, "conflict@example.com",
                        SECOND_CONFLICTING_PASSWORD_HASH, "ORDER_MANAGER", now);
            }

            try (PreparedStatement external = connection.prepareStatement("""
                    INSERT INTO public.external_identities
                        (id, user_id, provider, provider_subject, email_verified, linked_at)
                    VALUES (?, ?, 'GOOGLE', ?, TRUE, ?)
                    """)) {
                external.setObject(1, EXTERNAL_LINK_ID);
                external.setObject(2, FIRST_ACCOUNT);
                external.setString(3, "mapped-google-subject");
                external.setTimestamp(4, Timestamp.from(now));
                external.executeUpdate();

                external.setObject(1, UNRESOLVED_EXTERNAL_LINK_ID);
                external.setObject(2, CONFLICTING_ACCOUNT);
                external.setString(3, "unresolved-google-subject");
                external.setTimestamp(4, Timestamp.from(now));
                external.executeUpdate();
            }

            try (PreparedStatement membership = connection.prepareStatement("""
                    INSERT INTO public.tenant_memberships
                        (id, tenant_id, normalized_email, role, status, user_id, created_by, created_at, activated_at)
                    VALUES (?, ?, ?, 'TENANT_ADMIN', 'ACTIVE', ?, ?, ?, ?)
                    """)) {
                membership.setObject(1, UUID.fromString("30000000-0000-4000-8000-000000000001"));
                membership.setObject(2, TENANT_ID);
                membership.setString(3, "shared@example.com");
                membership.setObject(4, FIRST_ACCOUNT);
                membership.setObject(5, FIRST_ACCOUNT);
                membership.setTimestamp(6, Timestamp.from(now));
                membership.setTimestamp(7, Timestamp.from(now));
                membership.executeUpdate();

                membership.setObject(1, UUID.fromString("30000000-0000-4000-8000-000000000002"));
                membership.setObject(2, OTHER_TENANT_ID);
                membership.setString(3, "conflict@example.com");
                membership.setObject(4, CONFLICTING_ACCOUNT);
                membership.setObject(5, FIRST_ACCOUNT);
                membership.setTimestamp(6, Timestamp.from(now));
                membership.setTimestamp(7, Timestamp.from(now));
                membership.executeUpdate();
            }

            try (PreparedStatement invitation = connection.prepareStatement("""
                    INSERT INTO public.registration_invitations
                        (id, tenant_id, normalized_email, token_hash, role, created_by, created_at, expires_at)
                    VALUES (?, ?, ?, ?, 'ORDER_VIEWER', ?, ?, ?)
                    """)) {
                invitation.setObject(1, UUID.fromString("40000000-0000-4000-8000-000000000001"));
                invitation.setObject(2, TENANT_ID);
                invitation.setString(3, "invitee@example.com");
                invitation.setString(4, "a".repeat(64));
                invitation.setObject(5, FIRST_ACCOUNT);
                invitation.setTimestamp(6, Timestamp.from(now));
                invitation.setTimestamp(7, Timestamp.from(now.plusSeconds(86400)));
                invitation.executeUpdate();
            }

            try (PreparedStatement challenge = connection.prepareStatement("""
                    INSERT INTO public.email_verification_codes
                        (id, normalized_email, code_hash, created_at, expires_at, failed_attempts, status,
                         temporary_password_hash)
                    VALUES (?, 'verify-later@example.com', ?, ?, ?, 0, 'PENDING', ?)
                    """)) {
                challenge.setObject(1, UUID.fromString("50000000-0000-4000-8000-000000000001"));
                challenge.setString(2, "f".repeat(64));
                challenge.setTimestamp(3, Timestamp.from(now));
                challenge.setTimestamp(4, Timestamp.from(now.plusSeconds(900)));
                challenge.setString(5, PENDING_PASSWORD_HASH);
                challenge.executeUpdate();
            }
        }
    }

    private static void seedTenantMetadata(PostgreSQLContainer<?> postgres) throws Exception {
        Instant now = Instant.now();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (PreparedStatement tenant = connection.prepareStatement("""
                    INSERT INTO tenant_service.tenants
                        (id, name, slug, owner_user_id, status, created_at, updated_at)
                    VALUES (?, 'Existing tenant', 'existing-tenant', ?, 'ACTIVE', ?, ?)
                    """)) {
                tenant.setObject(1, TENANT_ID);
                tenant.setObject(2, FIRST_ACCOUNT);
                tenant.setTimestamp(3, Timestamp.from(now));
                tenant.setTimestamp(4, Timestamp.from(now));
                tenant.executeUpdate();
            }
            try (PreparedStatement owner = connection.prepareStatement("""
                    INSERT INTO tenant_service.tenant_memberships
                        (id, tenant_id, user_id, role, status, is_owner, joined_at, created_at, updated_at)
                    VALUES (?, ?, ?, 'OWNER', 'ACTIVE', TRUE, ?, ?, ?)
                    """)) {
                owner.setObject(1, UUID.fromString("60000000-0000-4000-8000-000000000001"));
                owner.setObject(2, TENANT_ID);
                owner.setObject(3, FIRST_ACCOUNT);
                owner.setTimestamp(4, Timestamp.from(now));
                owner.setTimestamp(5, Timestamp.from(now));
                owner.setTimestamp(6, Timestamp.from(now));
                owner.executeUpdate();
            }
            try (PreparedStatement registry = connection.prepareStatement("""
                    INSERT INTO public.tenants (id, slug, schema_name, status, created_at)
                    VALUES (?, 'existing-tenant', 'tenant_10000000-0000-4000-8000-000000000001', 'ACTIVE', ?)
                    """)) {
                registry.setObject(1, TENANT_ID);
                registry.setTimestamp(2, Timestamp.from(now));
                registry.executeUpdate();
            }
            connection.commit();
        }
    }

    private static void insertUser(
            PreparedStatement user,
            UUID id,
            UUID tenantId,
            String email,
            String passwordHash,
            String role,
            Instant now) throws Exception {
        user.setObject(1, id);
        user.setObject(2, tenantId);
        user.setString(3, email);
        user.setString(4, email);
        user.setString(5, passwordHash);
        user.setString(6, role);
        user.setTimestamp(7, Timestamp.from(now));
        user.setTimestamp(8, Timestamp.from(now));
        user.executeUpdate();
    }

    private static long queryLong(PostgreSQLContainer<?> postgres, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var results = statement.executeQuery(sql)) {
            results.next();
            return results.getLong(1);
        }
    }

    private static String queryString(PostgreSQLContainer<?> postgres, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var results = statement.executeQuery(sql)) {
            results.next();
            return results.getString(1);
        }
    }

    private static boolean queryBoolean(PostgreSQLContainer<?> postgres, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var results = statement.executeQuery(sql)) {
            results.next();
            return results.getBoolean(1);
        }
    }
}

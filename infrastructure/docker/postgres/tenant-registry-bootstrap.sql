SELECT format(
    'CREATE ROLE %I',
    :'auth_user'
)
WHERE NOT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = :'auth_user'
)
\gexec

SELECT format('CREATE ROLE %I', 'tenant_service')
WHERE NOT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = 'tenant_service'
)
\gexec

SELECT format('CREATE ROLE %I', 'tenant_service_migrator')
WHERE NOT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = 'tenant_service_migrator'
)
\gexec

SELECT format(
    'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
    :'auth_user',
    :'auth_password'
)
\gexec

ALTER ROLE tenant_service WITH LOGIN PASSWORD :'tenant_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE tenant_service_migrator WITH LOGIN PASSWORD :'tenant_migration_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;

GRANT CONNECT ON DATABASE tenantguard TO :"auth_user";
GRANT CONNECT ON DATABASE tenantguard TO tenant_service;
GRANT CONNECT ON DATABASE tenantguard TO tenant_service_migrator;
GRANT USAGE, CREATE ON SCHEMA public TO :"auth_user";
DO $$
DECLARE
    tenant_transfer_complete BOOLEAN := FALSE;
BEGIN
    IF to_regclass('tenant_service.tenant_service_flyway_schema_history') IS NOT NULL THEN
        EXECUTE $query$
            SELECT EXISTS (
                SELECT 1
                FROM tenant_service.tenant_service_flyway_schema_history
                WHERE version = '3'
                  AND success
            )
        $query$ INTO tenant_transfer_complete;
    END IF;

    IF tenant_transfer_complete THEN
        REVOKE USAGE, CREATE ON SCHEMA public FROM tenant_service_migrator;
        REVOKE USAGE ON SCHEMA auth_service FROM tenant_service_migrator;
    ELSE
        GRANT USAGE, CREATE ON SCHEMA public TO tenant_service_migrator;
    END IF;
END
$$;
CREATE SCHEMA IF NOT EXISTS auth_service AUTHORIZATION :"auth_user";
ALTER SCHEMA auth_service OWNER TO :"auth_user";
GRANT USAGE, CREATE ON SCHEMA auth_service TO :"auth_user";
CREATE SCHEMA IF NOT EXISTS tenant_service AUTHORIZATION tenant_service_migrator;
ALTER SCHEMA tenant_service OWNER TO tenant_service_migrator;
GRANT USAGE ON SCHEMA tenant_service TO tenant_service;
GRANT CREATE ON SCHEMA tenant_service TO tenant_service_migrator;
ALTER DEFAULT PRIVILEGES FOR ROLE tenant_service_migrator IN SCHEMA tenant_service
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tenant_service;
ALTER DEFAULT PRIVILEGES FOR ROLE tenant_service_migrator IN SCHEMA tenant_service
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO tenant_service;

SELECT format(
    'ALTER TABLE tenant_service.%I OWNER TO tenant_service_migrator',
    tablename
)
FROM pg_tables
WHERE schemaname = 'tenant_service'
\gexec

SELECT format(
    'ALTER SEQUENCE tenant_service.%I OWNER TO tenant_service_migrator',
    sequencename
)
FROM pg_sequences
WHERE schemaname = 'tenant_service'
\gexec

SELECT format(
    'ALTER FUNCTION tenant_service.%I(%s) OWNER TO tenant_service_migrator',
    p.proname,
    pg_get_function_identity_arguments(p.oid)
)
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'tenant_service'
\gexec

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA tenant_service TO tenant_service;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA tenant_service TO tenant_service;

DO $$
BEGIN
    IF to_regclass('public.auth_service_flyway_schema_history') IS NOT NULL
       AND to_regclass('auth_service.auth_service_flyway_schema_history') IS NOT NULL THEN
        RAISE EXCEPTION
            'Flyway history exists in both public and auth_service schemas; resolve the duplicate before startup';
    END IF;
END
$$;

SELECT format(
    'ALTER TABLE public.%I SET SCHEMA auth_service',
    'auth_service_flyway_schema_history'
)
WHERE to_regclass('public.auth_service_flyway_schema_history') IS NOT NULL
\gexec

SET ROLE tenant_service_migrator;

CREATE TABLE IF NOT EXISTS public.tenants (
    id UUID PRIMARY KEY,
    slug VARCHAR(128) NOT NULL UNIQUE,
    schema_name VARCHAR(63) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT tenants_status_check
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    CONSTRAINT tenants_schema_name_check
        CHECK (schema_name ~ '^tenant_[a-fA-F0-9-]+$')
);

RESET ROLE;

ALTER TABLE public.tenants OWNER TO tenant_service_migrator;
REVOKE ALL ON public.tenants FROM :"auth_user";

SELECT format(
    'ALTER TABLE public.%I OWNER TO %I',
    table_name,
    :'auth_user'
)
FROM unnest(ARRAY[
    'users',
    'registration_invitations',
    'external_identities',
    'tenant_memberships',
    'email_verification_codes',
    'auth_rate_limits'
]) AS table_name
WHERE to_regclass(format('public.%I', table_name)) IS NOT NULL
\gexec

SELECT format(
    'ALTER TABLE auth_service.%I OWNER TO %I',
    'auth_service_flyway_schema_history',
    :'auth_user'
)
WHERE to_regclass('auth_service.auth_service_flyway_schema_history') IS NOT NULL
\gexec

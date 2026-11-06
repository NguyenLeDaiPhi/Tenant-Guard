DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tenant_service') THEN
        CREATE ROLE tenant_service NOLOGIN;
    END IF;
END
$$;

SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L',
    :'auth_user',
    :'auth_password'
)
WHERE NOT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = :'auth_user'
)
\gexec

SELECT format(
    'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
    :'auth_user',
    :'auth_password'
)
\gexec

GRANT CONNECT ON DATABASE tenantguard TO :"auth_user";
GRANT USAGE, CREATE ON SCHEMA public TO :"auth_user";
GRANT USAGE, CREATE ON SCHEMA public TO tenant_service;
CREATE SCHEMA IF NOT EXISTS auth_service AUTHORIZATION :"auth_user";
ALTER SCHEMA auth_service OWNER TO :"auth_user";
GRANT USAGE, CREATE ON SCHEMA auth_service TO :"auth_user";

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

SET ROLE tenant_service;

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

GRANT SELECT ON public.tenants TO :"auth_user";

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

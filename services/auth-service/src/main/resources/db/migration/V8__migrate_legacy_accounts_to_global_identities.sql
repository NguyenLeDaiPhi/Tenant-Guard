ALTER TABLE public.external_identities
    ADD COLUMN global_user_id UUID REFERENCES public.user_identities(id);

ALTER TABLE public.external_identities
    ALTER COLUMN user_id DROP NOT NULL;

UPDATE public.users account
SET identity_id = identity.id
FROM public.user_identities identity
WHERE account.identity_id IS NULL
  AND account.normalized_email = identity.normalized_email
  AND account.password_hash = identity.password_hash;

WITH legacy_groups AS (
    SELECT
        normalized_email,
        (ARRAY_AGG(id ORDER BY id))[1] AS identity_id,
        (ARRAY_AGG(email ORDER BY id))[1] AS email,
        (ARRAY_AGG(password_hash ORDER BY id))[1] AS password_hash,
        BOOL_OR(status = 'PENDING_EMAIL') AS has_pending_email,
        BOOL_OR(status = 'LOCKED') AS has_locked_account,
        BOOL_OR(status = 'DISABLED') AS has_disabled_account,
        MAX(failed_login_count) AS failed_login_count,
        MAX(locked_until) AS locked_until,
        MAX(last_login_at) AS last_login_at,
        MIN(created_at) AS created_at,
        MAX(updated_at) AS updated_at
    FROM public.users
    WHERE identity_id IS NULL
    GROUP BY normalized_email
    HAVING COUNT(DISTINCT password_hash) = 1
),
eligible_groups AS (
    SELECT legacy_groups.*
    FROM legacy_groups
    WHERE NOT EXISTS (
        SELECT 1
        FROM public.user_identities identity
        WHERE identity.normalized_email = legacy_groups.normalized_email
           OR identity.id = legacy_groups.identity_id
    )
)
INSERT INTO public.user_identities (
    id,
    email,
    normalized_email,
    password_hash,
    status,
    created_at,
    updated_at,
    email_verified_at,
    failed_login_count,
    locked_until,
    last_login_at
)
SELECT
    identity_id,
    email,
    normalized_email,
    password_hash,
    CASE
        WHEN has_disabled_account THEN 'DISABLED'
        WHEN has_pending_email THEN 'PENDING_EMAIL'
        WHEN has_locked_account THEN 'LOCKED'
        ELSE 'VERIFIED'
    END,
    created_at,
    updated_at,
    CASE
        WHEN has_pending_email AND NOT has_disabled_account THEN NULL
        ELSE created_at
    END,
    failed_login_count,
    locked_until,
    last_login_at
FROM eligible_groups;

UPDATE public.users account
SET identity_id = identity.id
FROM public.user_identities identity
WHERE account.identity_id IS NULL
  AND account.normalized_email = identity.normalized_email
  AND account.password_hash = identity.password_hash;

CREATE TABLE auth_service.legacy_identity_migration_issues (
    source_account_id UUID PRIMARY KEY,
    normalized_email VARCHAR(320) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO auth_service.legacy_identity_migration_issues (
    source_account_id,
    normalized_email,
    reason
)
SELECT
    account.id,
    account.normalized_email,
    CASE
        WHEN EXISTS (
            SELECT 1
            FROM public.user_identities identity
            WHERE identity.normalized_email = account.normalized_email
        ) THEN 'IDENTITY_OR_PASSWORD_CONFLICT'
        WHEN (
            SELECT COUNT(DISTINCT grouped.password_hash)
            FROM public.users grouped
            WHERE grouped.normalized_email = account.normalized_email
        ) > 1 THEN 'CONFLICTING_LEGACY_PASSWORDS'
        ELSE 'UNRESOLVED_LEGACY_ACCOUNT'
    END
FROM public.users account
WHERE account.identity_id IS NULL;

UPDATE public.external_identities external_identity
SET global_user_id = account.identity_id
FROM public.users account
WHERE account.id = external_identity.user_id
  AND account.identity_id IS NOT NULL;

CREATE INDEX external_identities_global_user_provider_idx
    ON public.external_identities (global_user_id, provider);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tenant_service_migrator') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA public TO tenant_service_migrator';
        EXECUTE 'GRANT SELECT ON public.users, public.tenant_memberships, '
            || 'public.registration_invitations TO tenant_service_migrator';
    END IF;
END;
$$;

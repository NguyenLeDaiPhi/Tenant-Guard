DO $$
DECLARE
    expected_memberships BIGINT;
    expected_invitations BIGINT;
    transferred_memberships BIGINT;
    transferred_invitations BIGINT;
    transfer_completed_at TIMESTAMPTZ;
BEGIN
    SELECT
        expected_membership_count,
        expected_invitation_count,
        transferred_membership_count,
        transferred_invitation_count,
        completed_at
    INTO
        expected_memberships,
        expected_invitations,
        transferred_memberships,
        transferred_invitations,
        transfer_completed_at
    FROM auth_service.tenant_data_transfer_status
    WHERE id = 1;

    IF expected_memberships > 0
       OR expected_invitations > 0 THEN
        IF transfer_completed_at IS NULL
           OR transferred_memberships <> expected_memberships
           OR transferred_invitations <> expected_invitations THEN
            RAISE EXCEPTION
                'Tenant Service has not verified and completed the legacy data transfer';
        END IF;
    END IF;

    IF EXISTS (SELECT 1 FROM public.tenant_memberships)
       OR EXISTS (SELECT 1 FROM public.registration_invitations) THEN
        RAISE EXCEPTION
            'Legacy tenant memberships or invitations remain; complete Tenant Service transfer before Auth V10';
    END IF;
END;
$$;

CREATE TABLE auth_service.legacy_identity_migration_accounts (
    source_account_id UUID PRIMARY KEY,
    normalized_email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    failed_login_count INTEGER NOT NULL,
    locked_until TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    source_created_at TIMESTAMPTZ NOT NULL,
    source_updated_at TIMESTAMPTZ NOT NULL,
    migration_reason VARCHAR(64) NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO auth_service.legacy_identity_migration_accounts (
    source_account_id,
    normalized_email,
    password_hash,
    status,
    failed_login_count,
    locked_until,
    last_login_at,
    source_created_at,
    source_updated_at,
    migration_reason
)
SELECT
    account.id,
    account.normalized_email,
    account.password_hash,
    account.status,
    account.failed_login_count,
    account.locked_until,
    account.last_login_at,
    account.created_at,
    account.updated_at,
    COALESCE(issue.reason, 'UNRESOLVED_LEGACY_ACCOUNT')
FROM public.users account
LEFT JOIN auth_service.legacy_identity_migration_issues issue
  ON issue.source_account_id = account.id
WHERE account.identity_id IS NULL
ON CONFLICT (source_account_id) DO NOTHING;

CREATE TABLE auth_service.legacy_external_identity_mappings (
    source_external_identity_id UUID PRIMARY KEY,
    source_account_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    email_verified BOOLEAN NOT NULL,
    linked_at TIMESTAMPTZ NOT NULL,
    migration_reason VARCHAR(64) NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO auth_service.legacy_external_identity_mappings (
    source_external_identity_id,
    source_account_id,
    provider,
    provider_subject,
    email_verified,
    linked_at,
    migration_reason
)
SELECT
    external_identity.id,
    external_identity.user_id,
    external_identity.provider,
    external_identity.provider_subject,
    external_identity.email_verified,
    external_identity.linked_at,
    COALESCE(issue.reason, 'GLOBAL_IDENTITY_UNRESOLVED')
FROM public.external_identities external_identity
LEFT JOIN auth_service.legacy_identity_migration_issues issue
  ON issue.source_account_id = external_identity.user_id
WHERE external_identity.global_user_id IS NULL
ON CONFLICT (source_external_identity_id) DO NOTHING;

DELETE FROM public.external_identities
WHERE global_user_id IS NULL;

ALTER TABLE public.external_identities
    DROP CONSTRAINT external_identities_user_id_fkey,
    DROP CONSTRAINT external_identities_global_user_id_fkey,
    DROP COLUMN user_id,
    ALTER COLUMN global_user_id SET NOT NULL;

ALTER TABLE public.external_identities
    ADD CONSTRAINT external_identities_global_user_fk
        FOREIGN KEY (global_user_id) REFERENCES public.user_identities(id) ON DELETE CASCADE;

DROP INDEX IF EXISTS public.external_identities_user_provider_idx;

DROP TABLE public.tenant_memberships;
DROP TABLE public.registration_invitations;
DROP TABLE public.users;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tenant_service_migrator') THEN
        REVOKE ALL ON auth_service.tenant_data_transfer_status FROM tenant_service_migrator;
        REVOKE USAGE ON SCHEMA auth_service FROM tenant_service_migrator;
    END IF;
END;
$$;

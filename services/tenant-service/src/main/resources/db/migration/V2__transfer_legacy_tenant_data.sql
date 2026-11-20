CREATE TABLE tenant_service.tenant_invitations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant_service.tenants(id) ON DELETE CASCADE,
    normalized_email VARCHAR(320) NOT NULL,
    role VARCHAR(16) NOT NULL,
    token_hash VARCHAR(64),
    status VARCHAR(24) NOT NULL,
    invited_by UUID,
    accepted_user_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ,
    source_membership_id UUID,
    CONSTRAINT tenant_invitations_role_check CHECK (role IN ('ADMIN', 'MEMBER')),
    CONSTRAINT tenant_invitations_status_check
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED', 'NEEDS_REISSUE')),
    CONSTRAINT tenant_invitations_token_check
        CHECK (status <> 'PENDING' OR token_hash IS NOT NULL)
);

CREATE INDEX tenant_invitations_pending_email_idx
    ON tenant_service.tenant_invitations (tenant_id, normalized_email, created_at DESC)
    WHERE status = 'PENDING';
CREATE UNIQUE INDEX tenant_invitations_token_hash_uq
    ON tenant_service.tenant_invitations (token_hash)
    WHERE token_hash IS NOT NULL;

CREATE TABLE tenant_service.legacy_auth_memberships (
    source_membership_id UUID PRIMARY KEY,
    source_tenant_id UUID NOT NULL,
    normalized_email VARCHAR(320) NOT NULL,
    source_role VARCHAR(32) NOT NULL,
    source_status VARCHAR(16) NOT NULL,
    source_user_id UUID,
    source_created_by UUID NOT NULL,
    source_created_at TIMESTAMPTZ NOT NULL,
    source_activated_at TIMESTAMPTZ,
    mapped_user_id UUID,
    migration_status VARCHAR(40) NOT NULL,
    migration_note VARCHAR(200)
);

CREATE TABLE tenant_service.legacy_auth_invitations (
    source_invitation_id UUID PRIMARY KEY,
    source_tenant_id UUID NOT NULL,
    normalized_email VARCHAR(320) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    source_role VARCHAR(32) NOT NULL,
    source_created_by UUID NOT NULL,
    source_created_at TIMESTAMPTZ NOT NULL,
    source_expires_at TIMESTAMPTZ NOT NULL,
    source_consumed_at TIMESTAMPTZ,
    mapped_invited_by UUID,
    migration_status VARCHAR(40) NOT NULL,
    migration_note VARCHAR(200)
);

DO $$
BEGIN
    IF to_regclass('public.tenant_memberships') IS NOT NULL
       AND to_regclass('public.users') IS NOT NULL THEN
        INSERT INTO tenant_service.legacy_auth_memberships (
            source_membership_id,
            source_tenant_id,
            normalized_email,
            source_role,
            source_status,
            source_user_id,
            source_created_by,
            source_created_at,
            source_activated_at,
            mapped_user_id,
            migration_status,
            migration_note
        )
        SELECT
            membership.id,
            membership.tenant_id,
            membership.normalized_email,
            membership.role,
            membership.status,
            membership.user_id,
            membership.created_by,
            membership.created_at,
            membership.activated_at,
            account.identity_id,
            CASE
                WHEN membership.status = 'PENDING' THEN 'PENDING_NEEDS_INVITATION'
                WHEN tenant.id IS NULL THEN 'TENANT_NOT_FOUND'
                WHEN account.identity_id IS NULL THEN 'GLOBAL_IDENTITY_UNRESOLVED'
                ELSE 'IMPORT_ATTEMPTED'
            END,
            CASE
                WHEN membership.status = 'PENDING' THEN 'Invitation semantics are handled separately.'
                WHEN tenant.id IS NULL THEN 'Tenant metadata requires reconciliation before membership import.'
                WHEN account.identity_id IS NULL THEN 'Source account has no migrated global identity.'
                ELSE 'Source row retained for audit.'
            END
        FROM public.tenant_memberships membership
        LEFT JOIN public.users account ON account.id = membership.user_id
        LEFT JOIN tenant_service.tenants tenant ON tenant.id = membership.tenant_id;

        INSERT INTO tenant_service.tenant_memberships (
            id,
            tenant_id,
            user_id,
            role,
            status,
            is_owner,
            joined_at,
            created_at,
            updated_at
        )
        SELECT
            legacy.source_membership_id,
            legacy.source_tenant_id,
            legacy.mapped_user_id,
            CASE
                WHEN tenant.owner_user_id = legacy.mapped_user_id THEN 'OWNER'
                WHEN legacy.source_role = 'TENANT_ADMIN' THEN 'ADMIN'
                ELSE 'MEMBER'
            END,
            'ACTIVE',
            tenant.owner_user_id = legacy.mapped_user_id,
            COALESCE(legacy.source_activated_at, legacy.source_created_at),
            legacy.source_created_at,
            COALESCE(legacy.source_activated_at, legacy.source_created_at)
        FROM tenant_service.legacy_auth_memberships legacy
        JOIN tenant_service.tenants tenant ON tenant.id = legacy.source_tenant_id
        WHERE legacy.source_status = 'ACTIVE'
          AND legacy.mapped_user_id IS NOT NULL
        ON CONFLICT (tenant_id, user_id) DO NOTHING;

        UPDATE tenant_service.legacy_auth_memberships legacy
        SET migration_status = CASE
                WHEN legacy.source_status = 'PENDING' THEN 'PENDING_NEEDS_INVITATION'
                WHEN legacy.mapped_user_id IS NULL THEN legacy.migration_status
                WHEN NOT EXISTS (
                    SELECT 1
                    FROM tenant_service.tenants tenant
                    WHERE tenant.id = legacy.source_tenant_id
                ) THEN 'TENANT_NOT_FOUND'
                ELSE 'IMPORTED_OR_ALREADY_PRESENT'
            END;
    END IF;

    IF to_regclass('public.registration_invitations') IS NOT NULL
       AND to_regclass('public.users') IS NOT NULL THEN
        INSERT INTO tenant_service.legacy_auth_invitations (
            source_invitation_id,
            source_tenant_id,
            normalized_email,
            token_hash,
            source_role,
            source_created_by,
            source_created_at,
            source_expires_at,
            source_consumed_at,
            mapped_invited_by,
            migration_status,
            migration_note
        )
        SELECT
            invitation.id,
            invitation.tenant_id,
            invitation.normalized_email,
            invitation.token_hash,
            invitation.role,
            invitation.created_by,
            invitation.created_at,
            invitation.expires_at,
            invitation.consumed_at,
            inviter.identity_id,
            CASE
                WHEN tenant.id IS NULL THEN 'TENANT_NOT_FOUND'
                ELSE 'IMPORT_ATTEMPTED'
            END,
            CASE
                WHEN tenant.id IS NULL THEN 'Tenant metadata requires reconciliation before invitation import.'
                ELSE 'Source row retained for audit.'
            END
        FROM public.registration_invitations invitation
        LEFT JOIN public.users inviter ON inviter.id = invitation.created_by
        LEFT JOIN tenant_service.tenants tenant ON tenant.id = invitation.tenant_id;

        INSERT INTO tenant_service.tenant_invitations (
            id,
            tenant_id,
            normalized_email,
            role,
            token_hash,
            status,
            invited_by,
            accepted_user_id,
            created_at,
            expires_at,
            accepted_at
        )
        SELECT
            legacy.source_invitation_id,
            legacy.source_tenant_id,
            legacy.normalized_email,
            CASE WHEN legacy.source_role = 'TENANT_ADMIN' THEN 'ADMIN' ELSE 'MEMBER' END,
            legacy.token_hash,
            CASE
                WHEN legacy.source_consumed_at IS NOT NULL THEN 'ACCEPTED'
                WHEN legacy.source_expires_at <= CURRENT_TIMESTAMP THEN 'EXPIRED'
                ELSE 'PENDING'
            END,
            legacy.mapped_invited_by,
            NULL,
            legacy.source_created_at,
            legacy.source_expires_at,
            legacy.source_consumed_at
        FROM tenant_service.legacy_auth_invitations legacy
        JOIN tenant_service.tenants tenant ON tenant.id = legacy.source_tenant_id;

        UPDATE tenant_service.legacy_auth_invitations legacy
        SET migration_status = CASE
            WHEN NOT EXISTS (
                SELECT 1
                FROM tenant_service.tenants tenant
                WHERE tenant.id = legacy.source_tenant_id
            ) THEN 'TENANT_NOT_FOUND'
            ELSE 'IMPORTED'
        END;

        INSERT INTO tenant_service.tenant_invitations (
            id,
            tenant_id,
            normalized_email,
            role,
            token_hash,
            status,
            invited_by,
            accepted_user_id,
            created_at,
            expires_at,
            accepted_at,
            source_membership_id
        )
        SELECT
            gen_random_uuid(),
            legacy.source_tenant_id,
            legacy.normalized_email,
            CASE WHEN legacy.source_role = 'TENANT_ADMIN' THEN 'ADMIN' ELSE 'MEMBER' END,
            NULL,
            'NEEDS_REISSUE',
            inviter.identity_id,
            NULL,
            legacy.source_created_at,
            NULL,
            NULL,
            legacy.source_membership_id
        FROM tenant_service.legacy_auth_memberships legacy
        LEFT JOIN public.users inviter ON inviter.id = legacy.source_created_by
        JOIN tenant_service.tenants tenant ON tenant.id = legacy.source_tenant_id
        WHERE legacy.source_status = 'PENDING'
          AND NOT EXISTS (
              SELECT 1
              FROM tenant_service.tenant_invitations invitation
              WHERE invitation.tenant_id = legacy.source_tenant_id
                AND invitation.normalized_email = legacy.normalized_email
                AND invitation.status = 'PENDING'
          );
    END IF;
END;
$$;

DO $$
DECLARE
    legacy_index RECORD;
BEGIN
    IF to_regclass('public.tenants') IS NOT NULL THEN
        IF to_regclass('tenant_service.legacy_tenant_registry') IS NOT NULL THEN
            RAISE EXCEPTION 'Both public and archived tenant registries exist; reconcile before migrating';
        END IF;
        ALTER TABLE public.tenants RENAME TO legacy_tenant_registry;
        FOR legacy_index IN
            SELECT indexname
            FROM pg_indexes
            WHERE schemaname = 'public'
              AND tablename = 'legacy_tenant_registry'
        LOOP
            EXECUTE format(
                'ALTER INDEX public.%I RENAME TO %I',
                legacy_index.indexname,
                'legacy_' || legacy_index.indexname
            );
        END LOOP;
        ALTER TABLE public.legacy_tenant_registry SET SCHEMA tenant_service;
    END IF;
END;
$$;

REVOKE ALL ON tenant_service.legacy_tenant_registry FROM tenant_service;
REVOKE ALL ON tenant_service.legacy_auth_memberships FROM tenant_service;
REVOKE ALL ON tenant_service.legacy_auth_invitations FROM tenant_service;

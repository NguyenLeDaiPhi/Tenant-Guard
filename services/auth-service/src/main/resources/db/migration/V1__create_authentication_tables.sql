CREATE TABLE public.users (
    id UUID PRIMARY KEY,
    tenant_id UUID,
    email VARCHAR(320) NOT NULL,
    normalized_email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    failed_login_count INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT users_role_check
        CHECK (role IN (
            'PLATFORM_ADMIN',
            'TENANT_ADMIN',
            'ORDER_MANAGER',
            'ORDER_VIEWER'
        )),

    CONSTRAINT users_status_check
        CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),

    CONSTRAINT users_tenant_role_check
        CHECK (
            (role = 'PLATFORM_ADMIN' AND tenant_id IS NULL)
            OR
            (role <> 'PLATFORM_ADMIN' AND tenant_id IS NOT NULL)
        ),

    CONSTRAINT users_failed_login_count_check
        CHECK (failed_login_count >= 0)
);

CREATE UNIQUE INDEX users_tenant_email_uq
    ON public.users (tenant_id, normalized_email)
    WHERE tenant_id IS NOT NULL;

CREATE UNIQUE INDEX users_platform_admin_email_uq
    ON public.users (normalized_email)
    WHERE tenant_id IS NULL;

CREATE INDEX users_tenant_status_idx ON public.users (tenant_id, status);

CREATE TABLE public.registration_invitations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    normalized_email VARCHAR(320) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    role VARCHAR(32) NOT NULL,
    created_by UUID NOT NULL REFERENCES public.users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT registration_invitations_role_check
        CHECK (role IN ('TENANT_ADMIN', 'ORDER_MANAGER', 'ORDER_VIEWER')),
    CONSTRAINT registration_invitations_expiry_check CHECK (expires_at > created_at)
);

CREATE INDEX registration_invitations_tenant_email_idx
    ON public.registration_invitations (tenant_id, normalized_email);

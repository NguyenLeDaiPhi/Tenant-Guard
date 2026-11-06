ALTER TABLE public.users
    DROP CONSTRAINT users_status_check;

ALTER TABLE public.users
    ADD CONSTRAINT users_status_check
        CHECK (status IN ('PENDING_EMAIL', 'ACTIVE', 'LOCKED', 'DISABLED'));

CREATE TABLE public.tenant_memberships (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    normalized_email VARCHAR(320) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    user_id UUID UNIQUE REFERENCES public.users(id),
    created_by UUID NOT NULL REFERENCES public.users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at TIMESTAMPTZ,
    CONSTRAINT tenant_memberships_role_check
        CHECK (role IN ('TENANT_ADMIN', 'ORDER_MANAGER', 'ORDER_VIEWER')),
    CONSTRAINT tenant_memberships_status_check
        CHECK (status IN ('PENDING', 'ACTIVE')),
    CONSTRAINT tenant_memberships_user_status_check
        CHECK ((status = 'PENDING' AND activated_at IS NULL)
            OR (status = 'ACTIVE' AND user_id IS NOT NULL AND activated_at IS NOT NULL)),
    CONSTRAINT tenant_memberships_tenant_email_uq
        UNIQUE (tenant_id, normalized_email)
);

CREATE INDEX tenant_memberships_pending_email_idx
    ON public.tenant_memberships (normalized_email, created_at)
    WHERE status = 'PENDING';

INSERT INTO public.tenant_memberships (
    id, tenant_id, normalized_email, role, status, created_by, created_at
)
SELECT
    id, tenant_id, normalized_email, role, 'PENDING', created_by, created_at
FROM public.registration_invitations
WHERE consumed_at IS NULL
  AND expires_at > CURRENT_TIMESTAMP
ON CONFLICT (tenant_id, normalized_email) DO NOTHING;

CREATE TABLE public.email_verification_codes (
    id UUID PRIMARY KEY,
    normalized_email VARCHAR(320) NOT NULL,
    code_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT email_verification_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT email_verification_attempts_check CHECK (failed_attempts >= 0)
);

CREATE INDEX email_verification_pending_email_idx
    ON public.email_verification_codes (normalized_email, created_at DESC)
    WHERE consumed_at IS NULL;

CREATE TABLE public.auth_rate_limits (
    bucket_hash CHAR(64) PRIMARY KEY,
    window_started_at TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL,
    CONSTRAINT auth_rate_limits_count_check CHECK (request_count > 0)
);

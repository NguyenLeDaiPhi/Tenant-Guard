CREATE TABLE public.user_identities (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    normalized_email VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    email_verified_at TIMESTAMPTZ,
    CONSTRAINT user_identities_status_check
        CHECK (status IN ('PENDING_EMAIL', 'VERIFIED')),
    CONSTRAINT user_identities_verification_state_check
        CHECK (
            (status = 'PENDING_EMAIL' AND email_verified_at IS NULL)
            OR (status = 'VERIFIED' AND email_verified_at IS NOT NULL)
        )
);

ALTER TABLE public.users
    ADD COLUMN identity_id UUID REFERENCES public.user_identities(id);

CREATE UNIQUE INDEX users_tenant_identity_uq
    ON public.users (tenant_id, identity_id)
    WHERE identity_id IS NOT NULL AND tenant_id IS NOT NULL;

ALTER TABLE public.email_verification_codes
    ADD COLUMN identity_id UUID REFERENCES public.user_identities(id) ON DELETE CASCADE;

CREATE UNIQUE INDEX email_verification_one_active_identity_uq
    ON public.email_verification_codes (identity_id)
    WHERE identity_id IS NOT NULL AND consumed_at IS NULL;

CREATE INDEX email_verification_identity_idx
    ON public.email_verification_codes (identity_id, created_at DESC)
    WHERE identity_id IS NOT NULL;

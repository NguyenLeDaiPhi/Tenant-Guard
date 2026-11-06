CREATE TABLE public.external_identities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    linked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT external_identities_provider_check CHECK (provider IN ('GOOGLE')),
    CONSTRAINT external_identities_provider_subject_uq
        UNIQUE (provider, provider_subject)
);

CREATE INDEX external_identities_user_provider_idx
    ON public.external_identities (user_id, provider);

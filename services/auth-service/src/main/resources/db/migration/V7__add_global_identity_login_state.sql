ALTER TABLE public.user_identities
    ADD COLUMN failed_login_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN locked_until TIMESTAMPTZ,
    ADD COLUMN last_login_at TIMESTAMPTZ;

ALTER TABLE public.user_identities
    DROP CONSTRAINT user_identities_status_check,
    ADD CONSTRAINT user_identities_status_check
        CHECK (status IN ('PENDING_EMAIL', 'VERIFIED', 'LOCKED', 'DISABLED')),
    DROP CONSTRAINT user_identities_verification_state_check,
    ADD CONSTRAINT user_identities_verification_state_check
        CHECK (
            (status = 'PENDING_EMAIL' AND email_verified_at IS NULL)
            OR (status IN ('VERIFIED', 'LOCKED', 'DISABLED') AND email_verified_at IS NOT NULL)
        ),
    ADD CONSTRAINT user_identities_failed_login_count_check
        CHECK (failed_login_count >= 0);

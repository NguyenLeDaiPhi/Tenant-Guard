CREATE TEMPORARY TABLE pending_verification_migrations
ON COMMIT DROP
AS
SELECT DISTINCT ON (challenge.normalized_email)
    challenge.id AS challenge_id,
    challenge.normalized_email,
    challenge.temporary_password_hash,
    challenge.created_at
FROM public.email_verification_codes challenge
WHERE challenge.status = 'PENDING'
  AND challenge.consumed_at IS NULL
  AND challenge.expires_at > CURRENT_TIMESTAMP
  AND challenge.failed_attempts < 5
  AND challenge.temporary_password_hash IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM public.user_identities identity
      WHERE identity.normalized_email = challenge.normalized_email
        AND identity.status <> 'PENDING_EMAIL'
  )
ORDER BY challenge.normalized_email, challenge.created_at DESC, challenge.id DESC;

UPDATE public.user_identities identity
SET email = pending.normalized_email,
    password_hash = pending.temporary_password_hash,
    updated_at = CURRENT_TIMESTAMP
FROM pending_verification_migrations pending
WHERE identity.normalized_email = pending.normalized_email
  AND identity.status = 'PENDING_EMAIL';

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
    gen_random_uuid(),
    pending.normalized_email,
    pending.normalized_email,
    pending.temporary_password_hash,
    'PENDING_EMAIL',
    pending.created_at,
    pending.created_at,
    NULL,
    0,
    NULL,
    NULL
FROM pending_verification_migrations pending
WHERE NOT EXISTS (
    SELECT 1
    FROM public.user_identities identity
    WHERE identity.normalized_email = pending.normalized_email
);

DELETE FROM public.email_verification_codes challenge
WHERE NOT EXISTS (
    SELECT 1
    FROM pending_verification_migrations pending
    WHERE pending.challenge_id = challenge.id
);

UPDATE public.email_verification_codes challenge
SET identity_id = identity.id
FROM pending_verification_migrations pending
JOIN public.user_identities identity
  ON identity.normalized_email = pending.normalized_email
WHERE challenge.id = pending.challenge_id
  AND identity.status = 'PENDING_EMAIL';

DROP INDEX IF EXISTS public.email_verification_pending_email_idx;
DROP INDEX IF EXISTS public.email_verification_one_active_identity_uq;
DROP INDEX IF EXISTS public.email_verification_identity_idx;
DROP INDEX IF EXISTS public.email_verification_status_idx;

ALTER TABLE public.email_verification_codes
    DROP CONSTRAINT email_verification_status_check,
    DROP CONSTRAINT email_verification_codes_identity_id_fkey,
    DROP COLUMN normalized_email,
    DROP COLUMN temporary_password_hash,
    DROP COLUMN status,
    ALTER COLUMN identity_id SET NOT NULL;

ALTER TABLE public.email_verification_codes
    ADD CONSTRAINT email_verification_challenges_identity_fk
        FOREIGN KEY (identity_id) REFERENCES public.user_identities(id) ON DELETE CASCADE;

ALTER TABLE public.email_verification_codes
    RENAME TO email_verification_challenges;

ALTER TABLE public.email_verification_challenges
    RENAME CONSTRAINT email_verification_expiry_check
        TO email_verification_challenges_expiry_check;

ALTER TABLE public.email_verification_challenges
    RENAME CONSTRAINT email_verification_attempts_check
        TO email_verification_challenges_attempts_check;

ALTER TABLE public.email_verification_challenges
    RENAME CONSTRAINT email_verification_codes_code_hash_key
        TO email_verification_challenges_code_hash_key;

CREATE UNIQUE INDEX email_verification_one_active_identity_uq
    ON public.email_verification_challenges (identity_id)
    WHERE consumed_at IS NULL;

CREATE INDEX email_verification_pending_expiry_idx
    ON public.email_verification_challenges (expires_at)
    WHERE consumed_at IS NULL;

CREATE TABLE auth_service.tenant_data_transfer_status (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    expected_membership_count BIGINT NOT NULL CHECK (expected_membership_count >= 0),
    expected_invitation_count BIGINT NOT NULL CHECK (expected_invitation_count >= 0),
    transferred_membership_count BIGINT,
    transferred_invitation_count BIGINT,
    completed_at TIMESTAMPTZ,
    CONSTRAINT tenant_data_transfer_status_completion_check CHECK (
        (completed_at IS NULL
            AND transferred_membership_count IS NULL
            AND transferred_invitation_count IS NULL)
        OR
        (completed_at IS NOT NULL
            AND transferred_membership_count = expected_membership_count
            AND transferred_invitation_count = expected_invitation_count)
    )
);

INSERT INTO auth_service.tenant_data_transfer_status (
    id,
    expected_membership_count,
    expected_invitation_count
)
SELECT
    1,
    (SELECT COUNT(*) FROM public.tenant_memberships),
    (SELECT COUNT(*) FROM public.registration_invitations);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tenant_service_migrator') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA auth_service TO tenant_service_migrator';
        EXECUTE 'GRANT SELECT, UPDATE ON auth_service.tenant_data_transfer_status '
            || 'TO tenant_service_migrator';
        IF to_regclass('public.tenant_memberships') IS NOT NULL THEN
            EXECUTE 'GRANT DELETE ON public.tenant_memberships TO tenant_service_migrator';
        END IF;
        IF to_regclass('public.registration_invitations') IS NOT NULL THEN
            EXECUTE 'GRANT DELETE ON public.registration_invitations TO tenant_service_migrator';
        END IF;
    END IF;
END;
$$;

ALTER TABLE public.email_verification_codes
    ADD COLUMN temporary_password_hash VARCHAR(100);

UPDATE public.email_verification_codes code
SET temporary_password_hash = identity.password_hash
FROM public.user_identities identity
WHERE code.identity_id = identity.id
  AND identity.status = 'PENDING_EMAIL'
  AND code.status = 'PENDING'
  AND code.consumed_at IS NULL
  AND code.expires_at > CURRENT_TIMESTAMP;

DELETE FROM public.email_verification_codes
WHERE status <> 'PENDING'
   OR consumed_at IS NOT NULL
   OR expires_at <= CURRENT_TIMESTAMP
   OR temporary_password_hash IS NULL;

UPDATE public.email_verification_codes code
SET identity_id = NULL
WHERE EXISTS (
    SELECT 1
    FROM public.user_identities identity
    WHERE identity.id = code.identity_id
      AND identity.status = 'PENDING_EMAIL'
);

DELETE FROM public.user_identities identity
WHERE identity.status = 'PENDING_EMAIL'
  AND NOT EXISTS (
      SELECT 1
      FROM public.users account
      WHERE account.identity_id = identity.id
  );

ALTER TABLE public.email_verification_codes
    ALTER COLUMN temporary_password_hash SET NOT NULL;

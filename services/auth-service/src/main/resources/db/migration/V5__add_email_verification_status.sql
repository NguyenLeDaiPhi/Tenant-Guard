ALTER TABLE public.email_verification_codes
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING';

UPDATE public.email_verification_codes
SET status = CASE
    WHEN consumed_at IS NOT NULL THEN 'CONSUMED'
    WHEN expires_at <= CURRENT_TIMESTAMP THEN 'EXPIRED'
    ELSE 'PENDING'
END;

ALTER TABLE public.email_verification_codes
    ADD CONSTRAINT email_verification_status_check
        CHECK (status IN ('PENDING', 'CONSUMED', 'EXPIRED'));

CREATE INDEX email_verification_status_idx
    ON public.email_verification_codes (status, expires_at)
    WHERE status IN ('PENDING', 'EXPIRED');

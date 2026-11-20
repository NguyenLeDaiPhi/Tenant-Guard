DO $$
DECLARE
    source_count BIGINT;
    archived_count BIGINT;
    membership_count BIGINT := 0;
    invitation_count BIGINT := 0;
    transfer_tables_present BOOLEAN := FALSE;
BEGIN
    IF to_regclass('public.tenant_memberships') IS NOT NULL THEN
        transfer_tables_present := TRUE;
        IF to_regclass('tenant_service.legacy_auth_memberships') IS NULL THEN
            RAISE EXCEPTION 'Legacy membership archive was not created by Tenant V2';
        END IF;

        SELECT COUNT(*) INTO source_count FROM public.tenant_memberships;
        SELECT COUNT(*) INTO archived_count FROM tenant_service.legacy_auth_memberships;
        membership_count := source_count;
        IF source_count <> archived_count THEN
            RAISE EXCEPTION
                'Legacy membership transfer is incomplete: % source rows and % archived rows',
                source_count,
                archived_count;
        END IF;

        DELETE FROM public.tenant_memberships;
    END IF;

    IF to_regclass('public.registration_invitations') IS NOT NULL THEN
        transfer_tables_present := TRUE;
        IF to_regclass('tenant_service.legacy_auth_invitations') IS NULL THEN
            RAISE EXCEPTION 'Legacy invitation archive was not created by Tenant V2';
        END IF;

        SELECT COUNT(*) INTO source_count FROM public.registration_invitations;
        SELECT COUNT(*) INTO archived_count FROM tenant_service.legacy_auth_invitations;
        invitation_count := source_count;
        IF source_count <> archived_count THEN
            RAISE EXCEPTION
                'Legacy invitation transfer is incomplete: % source rows and % archived rows',
                source_count,
                archived_count;
        END IF;

        DELETE FROM public.registration_invitations;
    END IF;

    IF transfer_tables_present
       AND to_regclass('auth_service.tenant_data_transfer_status') IS NOT NULL THEN
        UPDATE auth_service.tenant_data_transfer_status
        SET transferred_membership_count = membership_count,
            transferred_invitation_count = invitation_count,
            completed_at = CURRENT_TIMESTAMP
        WHERE id = 1
          AND expected_membership_count = membership_count
          AND expected_invitation_count = invitation_count;
        IF NOT FOUND THEN
            RAISE EXCEPTION
                'Auth did not record the expected legacy membership and invitation counts';
        END IF;
    ELSIF membership_count > 0 OR invitation_count > 0 THEN
        RAISE EXCEPTION 'Auth V9 transfer status is missing; legacy source rows were not deleted';
    END IF;
END;
$$;

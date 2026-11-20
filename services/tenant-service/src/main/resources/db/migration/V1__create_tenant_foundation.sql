CREATE TABLE tenant_service.tenants (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    slug VARCHAR(128) NOT NULL UNIQUE,
    owner_user_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT tenants_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    CONSTRAINT tenants_slug_check CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
);

CREATE TABLE tenant_service.tenant_memberships (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant_service.tenants(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    is_owner BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT tenant_memberships_tenant_user_uq UNIQUE (tenant_id, user_id),
    CONSTRAINT tenant_memberships_role_check CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    CONSTRAINT tenant_memberships_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REMOVED')),
    CONSTRAINT tenant_memberships_owner_check CHECK (
        (is_owner AND role = 'OWNER') OR (NOT is_owner AND role <> 'OWNER')
    )
);

CREATE UNIQUE INDEX tenant_memberships_one_owner_uq
    ON tenant_service.tenant_memberships (tenant_id)
    WHERE is_owner;

ALTER TABLE tenant_service.tenants
    ADD CONSTRAINT tenants_owner_membership_fk
        FOREIGN KEY (id, owner_user_id)
        REFERENCES tenant_service.tenant_memberships (tenant_id, user_id)
        DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX tenant_memberships_user_status_idx
    ON tenant_service.tenant_memberships (user_id, status, tenant_id);

CREATE TABLE tenant_service.subscription_plans (
    id UUID PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT subscription_plans_code_check CHECK (code ~ '^[A-Z][A-Z0-9_]*$')
);

CREATE TABLE tenant_service.plan_versions (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES tenant_service.subscription_plans(id),
    version INTEGER NOT NULL,
    price_minor BIGINT,
    currency VARCHAR(3) NOT NULL,
    billing_interval VARCHAR(16) NOT NULL,
    publication_status VARCHAR(16) NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT plan_versions_plan_version_uq UNIQUE (plan_id, version),
    CONSTRAINT plan_versions_version_check CHECK (version > 0),
    CONSTRAINT plan_versions_price_check CHECK (price_minor IS NULL OR price_minor >= 0),
    CONSTRAINT plan_versions_currency_check CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT plan_versions_interval_check CHECK (billing_interval IN ('MONTH', 'YEAR', 'LIFETIME')),
    CONSTRAINT plan_versions_publication_check CHECK (publication_status IN ('DRAFT', 'PUBLISHED', 'RETIRED'))
);

CREATE INDEX plan_versions_current_idx
    ON tenant_service.plan_versions (plan_id, version DESC)
    WHERE publication_status = 'PUBLISHED';

CREATE TABLE tenant_service.plan_entitlements (
    id UUID PRIMARY KEY,
    plan_version_id UUID NOT NULL REFERENCES tenant_service.plan_versions(id) ON DELETE CASCADE,
    entitlement_key VARCHAR(128) NOT NULL,
    value_type VARCHAR(16) NOT NULL,
    boolean_value BOOLEAN,
    integer_value BIGINT,
    string_value VARCHAR(1000),
    unit VARCHAR(32),
    CONSTRAINT plan_entitlements_key_uq UNIQUE (plan_version_id, entitlement_key),
    CONSTRAINT plan_entitlements_type_check CHECK (
        (value_type = 'BOOLEAN' AND boolean_value IS NOT NULL AND integer_value IS NULL AND string_value IS NULL)
        OR (value_type = 'INTEGER' AND boolean_value IS NULL AND integer_value IS NOT NULL AND string_value IS NULL)
        OR (value_type = 'STRING' AND boolean_value IS NULL AND integer_value IS NULL AND string_value IS NOT NULL)
    )
);

CREATE TABLE tenant_service.tenant_subscriptions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL UNIQUE REFERENCES tenant_service.tenants(id) ON DELETE CASCADE,
    plan_version_id UUID NOT NULL REFERENCES tenant_service.plan_versions(id),
    status VARCHAR(24) NOT NULL,
    selected_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT tenant_subscriptions_status_check CHECK (status IN ('ACTIVE', 'PENDING_PAYMENT', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT tenant_subscriptions_lifecycle_check CHECK (
        (status = 'ACTIVE' AND started_at IS NOT NULL)
        OR (status <> 'ACTIVE' AND started_at IS NULL)
    )
);

CREATE TABLE tenant_service.subscription_entitlements (
    id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL REFERENCES tenant_service.tenant_subscriptions(id) ON DELETE CASCADE,
    entitlement_key VARCHAR(128) NOT NULL,
    value_type VARCHAR(16) NOT NULL,
    boolean_value BOOLEAN,
    integer_value BIGINT,
    string_value VARCHAR(1000),
    unit VARCHAR(32),
    CONSTRAINT subscription_entitlements_key_uq UNIQUE (subscription_id, entitlement_key),
    CONSTRAINT subscription_entitlements_type_check CHECK (
        (value_type = 'BOOLEAN' AND boolean_value IS NOT NULL AND integer_value IS NULL AND string_value IS NULL)
        OR (value_type = 'INTEGER' AND boolean_value IS NULL AND integer_value IS NOT NULL AND string_value IS NULL)
        OR (value_type = 'STRING' AND boolean_value IS NULL AND integer_value IS NULL AND string_value IS NOT NULL)
    )
);

CREATE TABLE IF NOT EXISTS public.tenants (
    id UUID PRIMARY KEY,
    slug VARCHAR(128) NOT NULL UNIQUE,
    schema_name VARCHAR(63) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT tenant_registry_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    CONSTRAINT tenant_registry_schema_name_check CHECK (schema_name ~ '^tenant_[a-fA-F0-9-]+$')
);

CREATE FUNCTION tenant_service.check_owner_membership_consistency()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    affected_tenant_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'tenants' THEN
        affected_tenant_id := COALESCE(NEW.id, OLD.id);
    ELSE
        affected_tenant_id := COALESCE(NEW.tenant_id, OLD.tenant_id);
    END IF;

    IF EXISTS (SELECT 1 FROM tenant_service.tenants WHERE id = affected_tenant_id)
       AND NOT EXISTS (
           SELECT 1
           FROM tenant_service.tenants tenant
           JOIN tenant_service.tenant_memberships membership
             ON membership.tenant_id = tenant.id
            AND membership.user_id = tenant.owner_user_id
            AND membership.is_owner
            AND membership.role = 'OWNER'
            AND membership.status = 'ACTIVE'
           WHERE tenant.id = affected_tenant_id
       ) THEN
        RAISE EXCEPTION 'tenant owner must have an active OWNER membership';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM tenant_service.tenant_memberships membership
        LEFT JOIN tenant_service.tenants tenant
          ON tenant.id = membership.tenant_id
         AND tenant.owner_user_id = membership.user_id
        WHERE membership.tenant_id = affected_tenant_id
          AND membership.is_owner
          AND (tenant.id IS NULL OR membership.role <> 'OWNER' OR membership.status <> 'ACTIVE')
    ) THEN
        RAISE EXCEPTION 'OWNER membership must match the tenant owner and be active';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER tenant_owner_consistency_from_tenant
    AFTER INSERT OR UPDATE OF owner_user_id ON tenant_service.tenants
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION tenant_service.check_owner_membership_consistency();

CREATE CONSTRAINT TRIGGER tenant_owner_consistency_from_membership
    AFTER INSERT OR UPDATE OR DELETE ON tenant_service.tenant_memberships
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION tenant_service.check_owner_membership_consistency();

INSERT INTO tenant_service.subscription_plans (id, code, name, description, active, created_at, updated_at)
VALUES
    ('10000000-0000-4000-8000-000000000001', 'FREE', 'Free', 'Development example plan; confirm limits before production.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('10000000-0000-4000-8000-000000000002', 'STARTER', 'Starter', 'Development example plan; pricing and limits require business approval.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('10000000-0000-4000-8000-000000000003', 'PROFESSIONAL', 'Professional', 'Development example plan; pricing and limits require business approval.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO tenant_service.plan_versions (id, plan_id, version, price_minor, currency, billing_interval, publication_status, effective_at, created_at)
VALUES
    ('20000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 1, 0, 'USD', 'LIFETIME', 'PUBLISHED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('20000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000002', 1, NULL, 'USD', 'MONTH', 'PUBLISHED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('20000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000003', 1, NULL, 'USD', 'MONTH', 'PUBLISHED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO tenant_service.plan_entitlements (id, plan_version_id, entitlement_key, value_type, boolean_value, integer_value, string_value, unit)
VALUES
    ('30000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001', 'orders.create', 'BOOLEAN', TRUE, NULL, NULL, NULL),
    ('30000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000001', 'orders.export', 'BOOLEAN', FALSE, NULL, NULL, NULL),
    ('30000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-000000000001', 'members.limit', 'INTEGER', NULL, 3, NULL, 'members'),
    ('30000000-0000-4000-8000-000000000004', '20000000-0000-4000-8000-000000000001', 'orders.monthlyLimit', 'INTEGER', NULL, 100, NULL, 'orders'),
    ('30000000-0000-4000-8000-000000000005', '20000000-0000-4000-8000-000000000002', 'orders.create', 'BOOLEAN', TRUE, NULL, NULL, NULL),
    ('30000000-0000-4000-8000-000000000006', '20000000-0000-4000-8000-000000000002', 'orders.export', 'BOOLEAN', TRUE, NULL, NULL, NULL),
    ('30000000-0000-4000-8000-000000000007', '20000000-0000-4000-8000-000000000002', 'members.limit', 'INTEGER', NULL, 10, NULL, 'members'),
    ('30000000-0000-4000-8000-000000000008', '20000000-0000-4000-8000-000000000002', 'orders.monthlyLimit', 'INTEGER', NULL, 1000, NULL, 'orders'),
    ('30000000-0000-4000-8000-000000000009', '20000000-0000-4000-8000-000000000003', 'orders.create', 'BOOLEAN', TRUE, NULL, NULL, NULL),
    ('30000000-0000-4000-8000-000000000010', '20000000-0000-4000-8000-000000000003', 'orders.export', 'BOOLEAN', TRUE, NULL, NULL, NULL),
    ('30000000-0000-4000-8000-000000000011', '20000000-0000-4000-8000-000000000003', 'analytics.advanced', 'BOOLEAN', TRUE, NULL, NULL, NULL),
    ('30000000-0000-4000-8000-000000000012', '20000000-0000-4000-8000-000000000003', 'members.limit', 'INTEGER', NULL, 50, NULL, 'members'),
    ('30000000-0000-4000-8000-000000000013', '20000000-0000-4000-8000-000000000003', 'orders.monthlyLimit', 'INTEGER', NULL, 10000, NULL, 'orders');

CREATE FUNCTION tenant_service.guard_published_plan_version()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.publication_status <> 'DRAFT' THEN
            RAISE EXCEPTION 'published plan versions cannot be deleted';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.publication_status <> 'DRAFT'
       AND (
           NEW.plan_id IS DISTINCT FROM OLD.plan_id
           OR NEW.version IS DISTINCT FROM OLD.version
           OR NEW.price_minor IS DISTINCT FROM OLD.price_minor
           OR NEW.currency IS DISTINCT FROM OLD.currency
           OR NEW.billing_interval IS DISTINCT FROM OLD.billing_interval
           OR NEW.effective_at IS DISTINCT FROM OLD.effective_at
           OR NEW.publication_status NOT IN (OLD.publication_status, 'RETIRED')
       ) THEN
        RAISE EXCEPTION 'published plan versions are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER plan_versions_immutable
    BEFORE UPDATE OR DELETE ON tenant_service.plan_versions
    FOR EACH ROW EXECUTE FUNCTION tenant_service.guard_published_plan_version();

CREATE FUNCTION tenant_service.guard_published_plan_entitlement()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    affected_version_id UUID;
BEGIN
    affected_version_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.plan_version_id ELSE NEW.plan_version_id END;

    IF EXISTS (
        SELECT 1
        FROM tenant_service.plan_versions
        WHERE id = affected_version_id AND publication_status <> 'DRAFT'
    ) THEN
        RAISE EXCEPTION 'published plan entitlements are immutable';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER plan_entitlements_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON tenant_service.plan_entitlements
    FOR EACH ROW EXECUTE FUNCTION tenant_service.guard_published_plan_entitlement();

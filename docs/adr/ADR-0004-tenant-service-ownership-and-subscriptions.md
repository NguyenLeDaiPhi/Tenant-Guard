# ADR-0004: Tenant ownership, JWT identity, and subscription snapshots

- Status: Superseded by ADR-0005
- Date: 2026-11-15

## Context

Tenant lifecycle and memberships belong to Tenant Service, while Auth Service owns global identity and credentials. PostgreSQL is shared locally but service schemas and Flyway history are isolated. Auth's current JWT subject is a tenant-scoped account UUID, so it is not always stable when one global identity has multiple tenant accounts.

## Decisions

1. Tenant, membership, subscription plan/version, entitlement, and subscription tables are owned by the `tenant_service` schema. No foreign keys cross into Auth Service tables.
2. Auth tokens retain the existing account UUID `sub` for compatibility and add `global_user_id`, derived from `user_identities.id` when linked or the account UUID for legacy accounts. Tenant Service validates the RSA signature, issuer, audience, time claims, and both UUID claims, and uses `global_user_id` as its logical user reference.
3. Tenant creation and active-plan resolution occur in one database transaction. The tenant, OWNER membership, selected plan version, entitlement snapshot, and compatibility registry row commit or roll back together.
4. `public.tenants` is maintained only as an Auth-readable compatibility projection because Auth currently uses it for tenant status checks. Tenant Service's schema is authoritative. The projection is not a cross-service foreign key.
5. Published plan versions and their entitlements are immutable. Changes require a new version. Every tenant subscription receives a copy of effective typed entitlements so future catalogue changes do not rewrite existing selections.
6. A zero-price plan activates immediately. Paid or unpriced plans remain `PENDING_PAYMENT`; Phase 1 does not claim payment success.

## Consequences

- Tenant Service can authorize users from its own membership data without querying Auth storage.
- Existing Auth endpoints continue using `sub`; downstream services that need cross-tenant identity should use `global_user_id`.
- The public tenant registry remains a temporary shared-database integration point and should eventually be replaced with a trusted Auth-to-Tenant status API or event contract.
- Development seed entitlements are examples, not commercial commitments.

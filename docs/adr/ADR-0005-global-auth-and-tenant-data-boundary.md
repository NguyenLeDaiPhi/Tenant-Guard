# ADR-0005: Global Auth and Tenant Data Boundary

- Status: Accepted
- Supersedes: ADR-0004

## Context

Auth and Tenant Service share a PostgreSQL database locally, but tenant-scoped accounts and membership rules had remained in Auth. The old Auth login also depended on tenant status and emitted tenant-oriented roles. These dependencies prevented a global identity from logging in independently of a tenant and made Auth responsible for tenant authorization.

## Decisions

1. Auth owns global identities, credentials, email verification, login lockout, external identity mappings, and global token issuance. Auth has no tenant membership controller, tenant status lookup, or tenant administrator bootstrap.
2. Auth issues RS256 JWTs with the global identity UUID as `sub` and `global_user_id`. Tokens contain no tenant ID or tenant role. Tenant Service validates the JWT and resolves tenant roles and access from its own database.
3. Tenant Service owns tenant metadata, memberships, invitations, subscriptions, and entitlement snapshots. There are no cross-service foreign keys or runtime cross-service database reads.
4. Tenant Service's runtime role has no read access to Auth-owned tables. During the staged one-time transfer, its migration role receives temporary access to selected legacy tables and a transfer-status row. Tenant V3 verifies archive counts before deleting the source membership and invitation rows; Auth V10 removes the legacy tables and revokes Auth-schema access.
5. Auth V8 backfills legacy account credentials only when accounts sharing an email have one unambiguous password hash. V10 archives unresolved credential hashes and external mappings, while `auth_service.legacy_identity_migration_issues` remains the reconciliation ledger. Conflicts are never silently merged or discarded.
6. Tenant V2 retains the old public tenant registry as `tenant_service.legacy_tenant_registry` and archives legacy memberships and invitations with migration outcomes. Active legacy tenant roles map conservatively: `TENANT_ADMIN` to `ADMIN`, order roles to `MEMBER`, and `OWNER` only when the migrated identity matches the Tenant Service tenant owner. Pending memberships without a reusable invitation require a reissue. Tenant V3 deletes the transferred Auth-side membership and invitation sources only after verifying they were archived.
7. Published subscription versions and their entitlement snapshots remain immutable as described in ADR-0004.

## Consequences

- A verified global identity can authenticate and receive a token without a tenant.
- Tenant creation establishes the caller's OWNER membership in one transaction. Tenant authorization never depends on Auth-side claims beyond the verified global subject.
- Legacy accounts with conflicting credentials require explicit reconciliation before they can use global password login. The migration issue and archive tables are operational review inputs, not authentication sources or cleanup queues.
- Tenant invitation and membership-administration workflows evolve within Tenant Service without adding tenant concerns back to Auth. Tenant Service exposes active membership listing, email invitation creation/listing/revocation, and email-bound invitation acceptance; membership role updates/removal remain future API work.

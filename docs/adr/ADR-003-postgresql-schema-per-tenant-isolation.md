# ADR-003: PostgreSQL Schema-per-Tenant Isolation

- Status: Accepted
- Date: 2026-11-20
- Approval basis: Accepted as the project baseline by the architecture-freeze request.

## Context

The blueprint requires PostgreSQL schema-per-tenant isolation for tenant-owned order records. Auth identity data and Tenant Service membership/catalogue data have different owners from order business data. The current repository has no Order Service implementation and does not provision or route per-tenant schemas.

## Problem Statement

The target must isolate order tables by tenant while resolving tenant context only after authenticating the global identity and checking current membership. Schema selection must not be controlled by an arbitrary client identifier or leak through pooled connections.

## Decision Drivers

- Order data must not be shared in one table relying solely on every query remembering a `tenant_id` predicate.
- Tenant identifiers must be immutable and server-controlled.
- Database ownership must follow service boundaries.
- Tenant context must be transaction-scoped and safe with connection pooling.
- Isolation claims must not imply independent compute or performance resources.

## Considered Options

1. **Shared order tables with a `tenant_id` column and row filtering:** rejected for the blueprint baseline because a missed predicate can expose another tenant's rows.
2. **One schema per tenant:** selected for Order Service as a practical logical data boundary that fits the blueprint and preserves one PostgreSQL service database.
3. **One database or PostgreSQL instance per tenant:** not selected for the initial target because operational overhead grows with tenant count; retain as an escalation for stronger resource or compliance isolation.

## Decision

Each service owns a separate logical database and credentials. A PostgreSQL cluster/instance may host multiple logical databases in local development, but database sharing does not permit cross-service table ownership or runtime reads.

- Auth Service database: global identity, credentials, verification challenges, external identity mappings, and Auth operational tables.
- Tenant Service database: central tenant metadata, membership, roles, invitations, subscriptions, entitlements, and provisioning state.
- Order Service database: Order Service platform/control metadata where needed, plus one tenant schema per tenant for order business tables.

Order schemas use the deterministic identifier `tenant_<32 lowercase hexadecimal UUID characters>`, derived only from the immutable tenant UUID. For example, UUID `3aa5f9a5-4b6e-49d8-bcc7-273ab209d951` maps to `tenant_3aa5f9a54b6e49d8bcc7273ab209d951`. Slug and name are never schema inputs. Tenant Service remains the authority for tenant metadata; an Order control table may record migration/provisioning state but is not a second tenant registry.

### Primary schema-routing mechanism

Use **transaction-local PostgreSQL `search_path`** on the transaction-bound connection after successful membership authorization:

```sql
SET LOCAL search_path TO "tenant_<32-lowercase-hex-uuid>", pg_catalog;
```

The schema identifier is constructed from a parsed UUID, checked against the exact generated format, and quoted as an SQL identifier. It is never concatenated from a client slug or raw tenant string. Platform/control tables are referenced with explicit schema qualification; do not put `public` or another tenant schema on the tenant search path.

The `SET LOCAL` statement and all tenant queries must use the same physical connection and transaction. PostgreSQL restores the previous setting at commit or rollback, preventing tenant context from persisting when the connection returns to the pool. Tenant data operations must not run outside this transaction boundary.

### Evaluated alternative: Hibernate multi-tenancy

Hibernate's `CurrentTenantIdentifierResolver` plus `MultiTenantConnectionProvider` can express tenant selection at the ORM connection layer. It is not the selected primary mechanism for the initial Order Service because it adds a second tenant-routing lifecycle and resource-acquisition path that must be coordinated with transactions and Flyway. The project selects one mechanism, transaction-local PostgreSQL `search_path`; it must not also install Hibernate schema switching unless a later ADR proves a specific need.

## Architecture and Responsibilities

| Responsibility | Owner |
|---|---|
| Confirm the user is a member of the requested tenant | Tenant Service, before Order data access |
| Derive the target schema name | Order Service, from the authorized tenant UUID |
| Create tenant schema and apply tenant order migrations | Order Service, using its migration principal |
| Execute order queries in the selected schema | Order Service, within one transaction |
| Store authoritative tenant slug/name/status/membership/plan | Tenant Service database |
| Store global identity and credentials | Auth Service database |

There are no cross-service foreign keys. UUIDs in Tenant and Order data are logical references; Auth data is not queried directly by Tenant or Order Service.

## Detailed Workflow

1. Order Service validates the JWT and asks Tenant Service for authorization for `(globalUserId, tenantId, action)`.
2. Tenant Service returns allow/deny only after checking current tenant status, active membership/role, and any relevant entitlement. Order Service verifies that provisioning is `READY`.
3. Order Service parses the UUID, derives the schema identifier, obtains its transaction-bound connection, and applies transaction-local `search_path`.
4. Order Service executes parameterized SQL/JPA operations. Client-controlled values remain SQL values, never identifiers.
5. Commit or rollback ends the transaction-local setting before the connection is reusable.
6. A tenant migration worker takes a per-tenant lock and applies versioned Order migrations to that tenant schema, recording migration history under Order Service ownership.

## Security Considerations

- Schema-per-tenant is a logical boundary, not a complete isolation boundary. It does not provide independent CPU, memory, disk I/O, connection capacity, or database performance.
- The Order runtime role must not own tenant schemas or have `CREATE` privileges. A separate migration principal creates schemas and DDL; the runtime principal receives only the necessary data privileges.
- Do not add a writable shared schema to `search_path`; use explicit qualification for control-plane tables to reduce shadowing risk.
- Parameterize SQL values. Validate and quote any SQL identifier derived from the UUID.
- Check membership and tenant state before schema access. A valid UUID or an existing schema is not proof of authorization.
- Apply pool limits, statement/query timeouts, rate limits, pagination, and bounded background-job concurrency. Monitor pool wait time, active connections, query latency, schema count, and database resource use.
- Schema-per-tenant is not adequate by itself where regulatory, noisy-neighbor, recovery, or performance requirements demand hard resource boundaries. Evaluate a dedicated database or PostgreSQL instance in those cases.

## Failure Scenarios

- Authorization fails or is unavailable: do not acquire a tenant data connection or query a tenant schema.
- Schema is absent or migration is incomplete: treat the tenant as not ready; return a controlled unavailable response and do not fall back to `public`.
- Transaction rolls back: all tenant changes roll back and `SET LOCAL` is cleared by PostgreSQL.
- Connection is reused: verify in integration tests that a subsequent tenant cannot observe the prior tenant's `search_path`.
- Migration fails partway: leave migration history and provisioning state diagnosable; retry only through the idempotent provisioning path.
- Identifier validation fails: reject before issuing any dynamic SQL.

## Consequences

### Positive

- Tenant order tables are separated into distinct namespaces.
- One tenant schema can be migrated and inspected independently.
- Tenant context is bound to the transaction rather than a long-lived thread or pooled connection.

### Negative

- Schema count and migration orchestration grow with tenant count.
- Schema changes must be applied and monitored across all tenants.
- A single Order database and role still share infrastructure and some failure/resource domains.

### Trade-offs

- This is stronger protection against accidental cross-tenant queries than a shared table alone, but it is not physical isolation.
- Transaction-local `search_path` reduces pool leakage risk but does not replace authorization or safe SQL.
- Separate logical databases improve service ownership while a shared local PostgreSQL instance keeps development practical.

## Implementation Status

**Not implemented for orders.** Auth and Tenant data currently use separate PostgreSQL schemas and DB roles, but Compose points both services at the same logical database, `tenantguard`. Tenant Service's fixed JPA/Flyway schema is `tenant_service`. Its migration history retains/archives a legacy `public.tenants` registry, but runtime tenant creation does not create per-tenant schemas. `services/order-service/` has no implementation. No `CurrentTenantIdentifierResolver`, `MultiTenantConnectionProvider`, or transaction-local tenant routing exists.

## Validation and Acceptance Criteria

- Every tenant schema name is derived from a canonical UUID and cannot contain client-controlled SQL.
- Integration tests prove Tenant A cannot read, update, or delete Tenant B order data by guessed ID or altered tenant selector.
- Tests reuse pooled connections across tenants and verify no schema context leaks after commit, rollback, exception, or async handoff.
- Tenant authorization is checked before schema acquisition.
- Migrations can be applied repeatedly and concurrently without duplicate schema creation or partial readiness.
- The runtime DB role cannot create schemas or run DDL.
- Load and query safeguards are measured and documented; claims distinguish logical schema isolation from resource isolation.

## References

- [Tenant Service migration V1](../../services/tenant-service/src/main/resources/db/migration/V1__create_tenant_foundation.sql)
- [Tenant Service database configuration](../../services/tenant-service/src/main/resources/application.properties)
- [Tenant Service PostgreSQL bootstrap](../../infrastructure/docker/postgres/tenant-registry-bootstrap.sql)
- [Docker Compose database URLs](../../docker-compose.yml)
- [Tenant application service](../../services/tenant-service/src/main/java/com/tenant/service/tenant/service/TenantApplicationService.java)
- [Architecture consistency report](../architecture/ARCHITECTURE-CONSISTENCY-REPORT.md)
- Project blueprint supplied with the task (external DOCX source).

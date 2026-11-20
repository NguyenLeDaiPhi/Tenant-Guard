# ADR-004: Tenant Provisioning and Lifecycle Management

- Status: Accepted
- Date: 2026-11-20
- Approval basis: Accepted as the project baseline by the architecture-freeze request.

## Context

Tenant Service currently creates the tenant row, initial OWNER membership, selected subscription, and entitlement snapshot in one local database transaction. It returns an active tenant immediately. The target architecture also requires Order Service to create and migrate that tenant's Order schema, but Order Service and cross-service provisioning are not implemented.

## Problem Statement

A Tenant Service database transaction cannot atomically create schema state in an Order Service database. The design must tolerate duplicate delivery, service outages, partial DDL, retries, and process restarts without making a partially provisioned tenant available for business operations.

## Decision Drivers

- Keep each service's database private to its owning service.
- Preserve the Tenant Service transaction for tenant, owner, subscription, and entitlement records.
- Coordinate Order provisioning durably without pretending a distributed transaction exists.
- Make provisioning idempotent and observable.
- Deny tenant business operations until all required Order migrations succeed.
- Keep tenant lifecycle status distinct from infrastructure provisioning state.

## Considered Options

1. **Create Order schema synchronously inside Tenant Service's transaction:** rejected; it would require direct cross-service database access and cannot be rolled back atomically with the Tenant DB.
2. **Best-effort fire-and-forget HTTP after tenant commit:** rejected; a crash between commit and request loses provisioning.
3. **Transactional outbox in Tenant Service with retryable authenticated HTTP command to Order Service:** selected for the initial coordination design. It uses no message broker and supports durable at-least-once delivery.
4. **Distributed transaction / 2PC across service databases:** rejected as unnecessary complexity and poor fit for schema DDL and independent service ownership.

## Decision

Tenant Service coordinates provisioning; Order Service provisions its own database. Tenant creation is a local transaction that writes the tenant, initial OWNER membership, selected plan/subscription and entitlement snapshot, and a provisioning outbox command. A dispatcher retries the command until Order Service reports a durable outcome.

Provisioning state is stored separately from tenant lifecycle status. The initial workflow uses:

`PENDING -> PROVISIONING -> READY`

On failure:

`PROVISIONING -> FAILED -> RETRYING -> PROVISIONING`

`READY` means the Order schema exists and all required migrations for the requested provisioning version have completed. Tenant-scoped business operations are permitted only when the tenant is active and provisioning is `READY`.

## Architecture and Responsibilities

| Concern | Owner |
|---|---|
| Tenant metadata, OWNER membership, plan/subscription, entitlement snapshot | Tenant Service transaction and database |
| Authoritative tenant lifecycle and provisioning state visible to application policy | Tenant Service |
| Outbox creation, delivery attempts, and retry scheduling | Tenant Service |
| Tenant schema name, schema creation, migration execution, and Order readiness record | Order Service and its database |
| Readiness gate before tenant business requests | Tenant Service authorization contract and Order Service |
| Platform-operator lifecycle actions | Tenant Service; operator authorization mechanism remains a separate open decision |

Do not overload `TenantStatus` (`ACTIVE`, `SUSPENDED`, `DEACTIVATED`) with provisioning values. Provisioning state is a separate field/state machine. Tenant roles remain `OWNER`, `ADMIN`, and `MEMBER`; a platform operator is not a global subscription or membership role.

## Detailed Workflow

1. A verified global identity sends a tenant-creation request. Tenant Service validates the Auth JWT and derives the global UUID from `sub`.
2. In one Tenant Service transaction, it validates the plan and writes the tenant in `PENDING`, the initial active OWNER membership, the selected subscription and entitlement snapshot, and a uniquely identified provisioning outbox command. The transaction commits locally.
3. The API returns the created tenant representation and its provisioning state. A client can poll an authenticated status/list endpoint; it must not assume the tenant is usable yet.
4. A Tenant Service dispatcher claims an outbox row with bounded retries and sends an authenticated internal request to Order Service. The command carries tenant UUID and provisioning/migration version, never a schema string supplied by the caller.
5. Order Service validates the service caller and command, derives the schema name from tenant UUID, records/locks the provisioning operation, creates the schema idempotently, and applies versioned tenant migrations.
6. Order Service returns a durable success or failure result. On success, Tenant Service marks the outbox command complete and tenant provisioning `READY`. On failure, it records the attempt and moves through `FAILED` or `RETRYING` according to retry policy.
7. For every Order request, Order Service validates the user's JWT, asks Tenant Service to authorize the user/action, and requires readiness before opening the tenant schema transaction.

### Idempotency and retry rules

- The command has a stable event ID and a unique key based on `(tenant UUID, operation, provisioning version)`.
- Delivery is at least once. Order Service must return the already recorded outcome for a duplicate command rather than create a second schema or repeat completed migrations unsafely.
- Schema creation and migration use a per-tenant lock and a migration history owned by Order Service.
- Retry transient network/database failures with bounded exponential backoff and jitter. After a configured limit, expose `FAILED`, retain diagnostics without secrets, and require an authorized retry or operator intervention.
- A retry resumes/reconciles the same provisioning version. It does not silently drop a partially created schema.

## Security Considerations

- Tenant Service validates the user's identity; Order Service independently validates the user JWT and authenticates Tenant Service's internal authorization/provisioning calls.
- Internal network reachability alone is not service authentication. Use protected transport and service credentials; do not introduce a service mesh solely to satisfy this decision.
- Only Order Service's migration principal may create schemas or run DDL. Application roles have no schema creation privilege.
- Tenant IDs and operation versions are validated; schema identifiers are derived and safely quoted by Order Service.
- Provisioning status and diagnostics are visible only to authorized tenant members or platform operators and never include tokens, credentials, or raw secrets.
- Failure and retry paths do not change membership roles or grant business access.

## Failure Scenarios

- Tenant Service transaction fails: no tenant, owner membership, subscription snapshot, or outbox command is committed.
- Tenant Service commits but the dispatcher crashes: the outbox remains and another worker retries.
- Order Service is unavailable: tenant remains `PENDING`/`RETRYING`; business operations remain denied.
- Order Service completes migrations but the response is lost: retry uses the same idempotency key and receives the recorded outcome.
- Migration fails after schema creation: preserve failure state and migration evidence; retry/reconcile under the same tenant lock; do not mark `READY`.
- Duplicate commands race: Order Service's unique idempotency record/lock serializes them.
- Tenant is deactivated during provisioning: completion must not make it operational; lifecycle authorization still denies it.
- Permanent failure: keep the tenant and owner records for diagnosis/retry; do not attempt a cross-database rollback. Subscription/payment compensation is a separate explicit business workflow.
- Deprovisioning: first deny new access and retain a tombstone; remove tenant schemas only through an authorized, auditable, retention-aware Order Service workflow after backup/retention obligations are met.

## Consequences

### Positive

- Tenant records and Order schemas remain owned by their respective services.
- Provisioning survives process crashes and can be retried.
- `READY` is an explicit gate rather than an assumption based on tenant-row existence.

### Negative

- Tenant creation is eventually consistent across services and can remain unavailable while provisioning runs.
- Outbox tables, workers, internal API authentication, retry monitoring, and operator recovery procedures must be implemented.

### Trade-offs

- At-least-once delivery and idempotent handlers are preferred over introducing Kafka or distributed transactions.
- Partial schema artifacts may remain after a failure; reconciliation is safer than automatic destructive rollback.
- The tenant is created before its Order data plane is ready, so API responses and dashboard states must expose provisioning status.

## Implementation Status

**Not implemented.** Tenant Service currently performs only its own transaction and sets tenant lifecycle `ACTIVE`; the service has no provisioning-state field, outbox, dispatcher, Order Service call, or readiness gate. Subscription status is independent (`ACTIVE` for a zero-price plan; `PENDING_PAYMENT` for paid or unpriced plans). There is no Order Service implementation or tenant-schema migration runner.

The blueprint also calls for platform-administrator tenant create/list/deactivate operations. Tenant Service currently exposes self-service creation/listing for the authenticated user's memberships and has no separate platform-operator authorization flow. The owner of that policy is Tenant Service; the operator identity/bootstrap mechanism remains **Proposed** and must not be represented as a global `USER`, `PRO`, or `ENTERPRISE` role.

## Validation and Acceptance Criteria

- Tenant plus owner membership, subscription/entitlements, and outbox row commit or roll back together in the Tenant DB.
- Outbox redelivery and concurrent duplicate commands create only one logical provisioning operation.
- A schema is not considered ready until all required migrations succeed.
- No order read/write is possible while provisioning is pending, retrying, failed, or tenant lifecycle is inactive.
- Dispatcher restart, Order Service outage, lost response, migration failure, and retry recovery have PostgreSQL integration tests.
- Provisioning status is observable to authorized callers; failure details are safe and actionable.
- Tenant deletion/deactivation does not cross service databases or remove Order data before retention and recovery policy is satisfied.
- The platform-operator authorization mechanism is separately approved and tested.

## References

- [TenantApplicationService.create](../../services/tenant-service/src/main/java/com/tenant/service/tenant/service/TenantApplicationService.java)
- [TenantController](../../services/tenant-service/src/main/java/com/tenant/service/tenant/controller/TenantController.java)
- [Tenant lifecycle status](../../services/tenant-service/src/main/java/com/tenant/service/tenant/entity/TenantStatus.java)
- [Tenant schema migration V1](../../services/tenant-service/src/main/resources/db/migration/V1__create_tenant_foundation.sql)
- [Docker Compose](../../docker-compose.yml)
- [Architecture freeze diagrams](../architecture/architecture-freeze-diagrams.md)
- Project blueprint supplied with the task (external DOCX source).

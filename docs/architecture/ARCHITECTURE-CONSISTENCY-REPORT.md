# TenantGuard OMS Architecture Consistency Report

- Date: 2026-11-20
- Scope: project blueprint, current Auth and Tenant source/configuration/migrations/tests, existing architecture docs and ADRs, Compose and infrastructure files.
- Governing decisions: [ADR-001](../adr/ADR-001-identity-and-authentication-ownership.md), [ADR-002](../adr/ADR-002-tenant-authorization-and-membership.md), [ADR-003](../adr/ADR-003-postgresql-schema-per-tenant-isolation.md), [ADR-004](../adr/ADR-004-tenant-provisioning-and-lifecycle.md).

## Executive Summary

Auth and Tenant Service implement the global identity and tenant membership boundary. Auth issues global-identity JWTs; Tenant Service validates those JWTs and reads membership/role state from its own schema. Tenant creation locally creates the tenant, OWNER membership, subscription, and entitlement snapshot transactionally.

The blueprint's Order Service and schema-per-tenant order data plane are not implemented. There is no Order Service code, Order database, per-tenant schema provisioning/routing, cross-service provisioning workflow, or readiness gate. The local Compose stack points Auth and Tenant at the same logical PostgreSQL database (`tenantguard`) while using different schemas and DB roles; this is partial service separation, not the accepted target of one database per service.

The global identity design is consistent with the accepted freeze decisions. Full system acceptance is not met until the planned Order and provisioning components are implemented and negative isolation tests are added.

## Status Matrix

| Area | Intended decision | Current implementation evidence | Status | Required action |
|---|---|---|---|---|
| Identity ownership | Auth owns global identities and authentication; identity exists without a tenant. | Auth registration and login use `UserIdentity`; login request contains email/password only. Auth V10 removes legacy tenant membership/account tables. | **ALIGNED** | Preserve the boundary; add/keep regression tests for login with zero memberships. |
| JWT contract | `sub` is global UUID; no tenant ID, role, or entitlement claim is authoritative. | `AccessTokenService` emits `sub` and `global_user_id` as the global UUID, plus issuer/audience/time/email; no tenant claims. | **ALIGNED** | Maintain exact validation and key-rotation tests for every consumer. |
| JWT validation | Every consuming service verifies signature, issuer, audience, time claims, and subject semantics. | Tenant `JwtSecurityConfiguration` checks RS256/JWKS, issuer, audience, time, UUID `sub`, and equality with `global_user_id`. Auth's own `JwtConfiguration.jwtDecoder()` configures a public key but no explicit issuer/audience validator. Order consumer does not exist. | **PARTIALLY ALIGNED** | Add explicit issuer/audience validation to Auth JWT-consuming routes; implement the same contract in Order Service. Governed by ADR-001. |
| Tenant membership | Tenant Service owns tenant metadata, memberships, roles, invitations, and authoritative membership state. | `tenant_service` tables and Tenant Service controllers/services implement active membership listing, invitations, and tenant-scoped `OWNER`/`ADMIN`/`MEMBER` roles; no cross-service FK to Auth. | **ALIGNED** | Keep tenant role data in Tenant Service. Add role-change/removal APIs only with explicit policy and tests. |
| Tenant authorization | Tenant Service determines current membership/policy; business services enforce the decision before data access. | Tenant APIs authorize membership/role checks. There is no Order Service or Tenant authorization contract for Order requests. | **PARTIALLY ALIGNED** | Define protected Order-to-Tenant authorization contract; deny on dependency failure and test cross-tenant requests. Governed by ADR-002. |
| Subscription ownership | Plans, subscriptions, and entitlements are tenant capabilities owned by Tenant Service. | Tenant Service stores versioned plans and subscription entitlement snapshots; tenant creation persists them in the creation transaction. | **ALIGNED** | Keep plan/entitlement values out of global identity roles; add Order entitlement enforcement with Order API. |
| Service database ownership | Each service owns its logical database and access credentials; no runtime cross-service database reads. | Compose configures Auth and Tenant to the same database `tenantguard`, with separate `auth_service`/`tenant_service` schemas and roles. A one-time staged legacy migration grants temporary migration-role access to selected legacy tables. | **PARTIALLY ALIGNED** | Separate logical databases and credentials while a local PostgreSQL server may remain shared. Keep migration-only access bounded and revoked. Governed by ADR-003. |
| Order Service | Order Service owns order APIs, order business data, and tenant-scoped transactions. | `services/order-service/` has no implementation files; no Order API, entities, database, Compose service, or integration tests exist. | **NOT IMPLEMENTED** | Build only after authorization and data-isolation contracts are stable. Do not describe it as operational. |
| Schema isolation | Order DB has one schema per tenant, derived from immutable tenant UUID. | No Order Service. Tenant migration creates centralized `tenant_service` tables and retains/archives a legacy public tenant registry; Tenant creation does not provision tenant schemas. | **NOT IMPLEMENTED** | Implement schema derivation/provisioning under Order Service only, with isolation tests. Governed by ADR-003. |
| Schema routing | Authorized tenant context is applied safely within a transaction and cannot leak through the pool. | Tenant Service is configured with fixed `tenant_service` default schema. No `SET LOCAL search_path`, Hibernate tenant resolver/provider, or Order data access exists. | **NOT IMPLEMENTED** | Implement ADR-003's single selected mechanism: transaction-local PostgreSQL `search_path`; test pooled connection reuse. |
| Tenant provisioning | Tenant creation durably coordinates Order schema creation/migrations; business operations wait for `READY`. | `TenantApplicationService.create()` transactionally writes tenant, OWNER membership, subscription, and entitlements, and marks lifecycle `ACTIVE`. No provisioning state, outbox, dispatcher, Order call, or readiness gate exists. | **NOT IMPLEMENTED** | Add separate provisioning state and transactional outbox; Order idempotently provisions and reports status. Governed by ADR-004. |
| Migration strategy | Auth/Tenant migrations are repeatable; Order migrations run per tenant schema under Order ownership. | Auth and Tenant use Flyway and integration tests run PostgreSQL Testcontainers. No Order or per-tenant migration history/runner exists. | **PARTIALLY ALIGNED** | Implement Order platform and tenant-schema migrations, locking, retries, and partial-failure recovery. Governed by ADR-003/004. |
| Tenant lifecycle administration | Tenant Service owns active/suspended/deactivated lifecycle; platform operator access is distinct from member roles. | Tenant rows support `ACTIVE`, `SUSPENDED`, `DEACTIVATED`; current APIs expose self-service create/list/get/name update, not platform-admin list/deactivate operations. No operator authorization mechanism is evident. | **PARTIALLY ALIGNED** | Define operator identity/bootstrap and protected lifecycle APIs without making subscription or membership roles global. Governed by ADR-004; operator mechanism remains Proposed. |

## Conflict Details

### Historical diagrams imply current tenant-scoped JWTs and an operational Order Service

The existing [TenantGuard_OMS_Mermaid_Diagrams.md](./TenantGuard_OMS_Mermaid_Diagrams.md) contains older examples using JWT `tid`, `public.tenants`, an Order Service, schema routing, and a `MultiTenantConnectionProvider`. Current Auth code issues global-subject tokens without `tid`; the Order Service and routing implementation do not exist. Treating those drawings as current behavior would misstate both the security contract and implementation status.

The document is now labeled as a historical target sketch. The canonical accepted ownership and target-state diagrams are [architecture-freeze-diagrams.md](./architecture-freeze-diagrams.md). The old diagrams should be retired or rewritten when the target implementation is delivered.

### Root README overstates implementation

The root [README.md](../../README.md) previously listed Order processing, tenant schema provisioning, per-tenant schemas, and scoped JWT claims as existing behavior. Current source does not support those claims. The README has been corrected to label Order Service/schema provisioning as planned and to describe the global JWT and Tenant Service membership checks accurately.

### Blueprint database wording and service ownership

The blueprint asks for a shared PostgreSQL database and separate tenant schemas; the architecture-freeze instruction requires each microservice to own its database. The accepted reconciliation is separate logical databases per service, which may share one local PostgreSQL cluster. The Order Service database then contains its own control schema and per-tenant business schemas. Current Compose still uses one logical database for Auth and Tenant, so the target is not yet realized.

### Platform administrator lifecycle

The blueprint's FR-01 calls for an authorized platform administrator to create/list/deactivate tenants. Current Tenant Service permits self-service creation and lists only the authenticated user's memberships; it does not expose a distinct operator lifecycle API. The service ownership is settled (Tenant Service), but operator identity/bootstrap and permission policy remain Proposed. Do not solve this by treating `PRO`/`ENTERPRISE` subscription codes or tenant membership roles as global administrator roles.

## Validation Evidence and Limits

- Auth endpoints and token issuance are in [AuthController](../../services/auth-service/src/main/java/com/authservice/api/AuthController.java), [AccessTokenService](../../services/auth-service/src/main/java/com/authservice/service/AccessTokenService.java), and [JwtConfiguration](../../services/auth-service/src/main/java/com/authservice/config/JwtConfiguration.java).
- Tenant JWT validation is in [JwtSecurityConfiguration](../../services/tenant-service/src/main/java/com/tenant/service/config/JwtSecurityConfiguration.java); caller identity is read by [CurrentUserProvider](../../services/tenant-service/src/main/java/com/tenant/service/security/CurrentUserProvider.java).
- Membership/role decisions are in [TenantAuthorizationService](../../services/tenant-service/src/main/java/com/tenant/service/security/TenantAuthorizationService.java).
- Tenant and subscription creation are in [TenantApplicationService](../../services/tenant-service/src/main/java/com/tenant/service/tenant/service/TenantApplicationService.java); schema and constraints are in [Tenant V1 migration](../../services/tenant-service/src/main/resources/db/migration/V1__create_tenant_foundation.sql).
- The Auth-to-Tenant legacy transfer is staged in [Tenant V2](../../services/tenant-service/src/main/resources/db/migration/V2__transfer_legacy_tenant_data.sql), [Tenant V3](../../services/tenant-service/src/main/resources/db/migration/V3__complete_legacy_auth_data_transfer.sql), and [Auth V10](../../services/auth-service/src/main/resources/db/migration/V10__remove_legacy_auth_and_tenant_tables.sql). This is migration-time handoff, not a runtime cross-service data API.
- [docker-compose.yml](../../docker-compose.yml) points Auth and Tenant at the same logical database and configures separate schemas/roles.
- Source inventory found no Order Service implementation. Existing integration suites verify Auth and Tenant behaviors, not Order schema routing or cross-tenant order isolation.

This report validates documents and source structure; it does not assert a deployment, performance result, security scan, or Order Service test result.

## Decisions Still Proposed

1. Platform-operator identity/bootstrap and authorization policy for tenant-wide administrative lifecycle operations.
2. Exact authenticated internal service credential mechanism for Order-to-Tenant authorization/provisioning calls.
3. Operational thresholds for retry limits, authorization timeouts, pool sizing, query timeouts, and tenant-count thresholds for dedicated database escalation.

These are bounded implementation choices. They do not reopen the accepted ownership boundaries or schema-per-tenant target.

## Required Implementation Order

1. Add Auth's explicit issuer/audience validation and make external identity linking consume a verified provider assertion.
2. Define and test Tenant Service's tenant-action/entitlement authorization contract for business services.
3. Implement Order Service with its own logical database and transaction-local schema selection; prove cross-tenant denial and pool isolation with PostgreSQL tests.
4. Add Tenant provisioning state and transactional outbox, idempotent Order schema migrations, retries, and `READY` gating.
5. Add operator lifecycle controls, service-level CI/scans, Compose database separation, and current-state documentation as each feature becomes real.
6. Only then add broader Kubernetes, network-policy, observability, and measured performance evidence from the blueprint. Do not claim those gates are met based on architecture diagrams.

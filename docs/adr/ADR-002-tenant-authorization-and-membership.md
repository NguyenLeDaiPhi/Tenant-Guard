# ADR-002: Tenant Authorization and Membership

- Status: Accepted
- Date: 2026-11-20
- Approval basis: Accepted as the project baseline by the architecture-freeze request.

## Context

Auth establishes a global identity. Tenant Service maintains the tenant, membership, role, invitation, subscription, and entitlement records. A user may belong to multiple tenants and may have a different role in each.

## Problem Statement

Tenant selection is necessary for tenant-scoped work, but a selected tenant ID is client input and cannot prove membership. Services need a consistent, current authorization decision without placing tenant roles in global identity tokens.

## Decision Drivers

- Tenant access must be checked server-side for every tenant-scoped operation.
- Membership changes must affect subsequent authorization decisions.
- Tenant roles must remain tenant-scoped.
- Subscription entitlements describe tenant capabilities, not user identity.
- Business services must not query another service's database directly.

## Considered Options

1. **Trust a client-supplied tenant ID or role:** rejected; client input is not an authorization credential.
2. **Put tenant IDs and roles in the access token:** rejected as an authority because membership and role changes would be stale until token refresh.
3. **Let every service read Tenant Service tables directly:** rejected because it violates service-owned data boundaries.
4. **Tenant Service owns authorization data and exposes a trusted authorization contract to business services:** selected.

## Decision

Tenant Service owns tenant metadata, memberships, membership roles, invitations, subscription plans, subscriptions, and entitlement snapshots. Membership roles are `OWNER`, `ADMIN`, and `MEMBER`; they apply only within a particular tenant.

An application-selected tenant ID may be sent in a path or header. Tenant Service must resolve the authenticated global UUID from a validated JWT and determine whether that identity has an active membership and sufficient permission. Subscription entitlements are evaluated as tenant capabilities, not as global roles.

Before any tenant-scoped Order Service operation, Order Service must enforce an authorization decision based on current Tenant Service data. It must not trust a JWT tenant claim, client-supplied user ID, role, or schema name.

## Architecture and Responsibilities

| Question | Authority |
|---|---|
| Who authenticates the user? | Auth Service validates credentials and issues the global JWT. |
| Who identifies the caller to a service? | Each API validates the JWT and uses its `sub` as the global UUID. |
| Who determines tenant membership and current membership role? | Tenant Service, from its own database. |
| Who decides tenant policy and current tenant state? | Tenant Service. It checks active membership, tenant lifecycle, and role policy. |
| Who checks whether a tenant feature is included in its plan? | Tenant Service owns the entitlement record; the business service must enforce the relevant entitlement before performing the feature. |
| Who executes order policy and data access? | Order Service, after receiving/validating tenant authorization and enforcing readiness and operation-specific rules. |

### Current role examples

Tenant Service currently permits active members to read a tenant, requires OWNER to update its name, and permits OWNER/ADMIN membership-management operations with restrictions on which roles an ADMIN may invite. These are existing Tenant Service rules, not a complete future Order permission matrix. Before Order Service ships, the project must specify and test the allowed order actions for each membership role.

## Detailed Workflow

1. A client obtains a global token from Auth and fetches its tenant list from Tenant Service.
2. The user selects a tenant. The selected ID is request context only.
3. The client calls Order Service with the global bearer token and selected tenant ID.
4. Order Service validates the JWT independently, including signature, issuer, audience, expiry, not-before time, and global subject.
5. Order Service sends an authorization request to Tenant Service containing the tenant selector and requested action. The end-user identity comes from the validated JWT, not a caller-supplied user ID. The internal call must authenticate the calling service as well as carry the end-user identity.
6. Tenant Service validates the user token, checks the tenant's lifecycle and provisioning readiness, looks up the current active membership, applies its role policy, and returns an allow/deny decision with only the required tenant role/entitlement context.
7. Order Service denies on a negative decision, timeout, invalid response, or authorization dependency failure. On allow, it applies the relevant entitlement and operates only in the schema derived from the validated tenant UUID.
8. Membership suspension/removal or tenant deactivation is reflected on the next authorization check. No positive authorization cache is allowed in the initial implementation; any later cache requires a documented revocation-delay bound.

## Security Considerations

- Tenant IDs, slugs, role names, and schema names supplied by a client are never proof of access.
- Tenant Service derives user identity from `sub`; it does not accept a body `userId` or requested membership role as the authenticated caller's authority.
- Order-to-Tenant authorization is an internal API contract, not direct database access. It must use protected service-to-service transport and authenticate the calling service.
- Authorization is fail-closed. Tenant Service unavailability must not cause Order Service to continue with cached or client-asserted permission.
- Cross-tenant record lookup should return a non-disclosing result, normally the same not-found response as an absent record.
- Authorization checks must precede tenant schema access; do not reveal whether a guessed tenant or order exists.

## Failure Scenarios

- Invalid JWT: reject with 401 before evaluating membership.
- Valid identity with no membership, suspended membership, inactive tenant, or insufficient role: deny without exposing tenant data.
- Tenant Service authorization timeout/error: deny or return a controlled dependency-unavailable response; never fail open.
- Subscription entitlement is absent or expired: deny the capability even if the user has an otherwise valid membership.
- A malformed or mismatched tenant selector: reject before constructing a database identifier.

## Consequences

### Positive

- One identity can belong to many tenants with independent roles.
- Membership revocation can take effect without waiting for a user to obtain a new JWT.
- Auth, Tenant, and Order responsibilities remain separate.

### Negative

- Order requests depend on Tenant Service availability and add an authorization call.
- The permission contract and internal service authentication mechanism must be implemented and operated.

### Trade-offs

- Online checks favor authorization freshness over lower latency and independence.
- A service outage reduces tenant-scoped availability by design.
- Future caching requires an explicit security/revocation trade-off.

## Implementation Status

**Partially aligned.** Tenant Service stores tenant-scoped `OWNER`, `ADMIN`, and `MEMBER` roles, checks membership and role on its own APIs, filters membership listings, and binds invitation acceptance to the authenticated email. Auth tokens do not contain tenant roles or tenant IDs. The Order Service, an Order-to-Tenant authorization endpoint, and order entitlement enforcement do not exist. Tenant Service currently has no role-update or membership-removal API.

## Validation and Acceptance Criteria

- Two users in the same tenant can have different membership roles; one global identity can have multiple tenant memberships.
- Altering a tenant path/header cannot grant access without an active membership.
- A non-member and an insufficient-role member cannot list, read, mutate, or cancel another tenant's orders.
- Membership removal/suspension and tenant deactivation deny the next protected request.
- Authorization and entitlement service failures are fail-closed.
- A tenant ID, user ID, role, or entitlement supplied by a client is never trusted.
- Authorization tests exercise the actual Tenant Service policy and Order Service enforcement, not mocks alone.

## References

- [TenantAuthorizationService](../../services/tenant-service/src/main/java/com/tenant/service/security/TenantAuthorizationService.java)
- [CurrentUserProvider](../../services/tenant-service/src/main/java/com/tenant/service/security/CurrentUserProvider.java)
- [MembershipService](../../services/tenant-service/src/main/java/com/tenant/service/membership/service/MembershipService.java)
- [TenantInvitationService](../../services/tenant-service/src/main/java/com/tenant/service/membership/service/TenantInvitationService.java)
- [Membership roles](../../services/tenant-service/src/main/java/com/tenant/service/membership/entity/MembershipRole.java)
- [Tenant Service integration tests](../../services/tenant-service/src/test/java/com/tenant/service/TenantServiceApplicationTests.java)
- [ADR-0005: Global Auth and Tenant Data Boundary](./ADR-0005-global-auth-and-tenant-data-boundary.md)
- [Architecture freeze diagrams](../architecture/architecture-freeze-diagrams.md)

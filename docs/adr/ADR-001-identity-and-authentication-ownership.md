# ADR-001: Identity and Authentication Ownership

- Status: Accepted
- Date: 2026-11-20
- Approval basis: Accepted as the project baseline by the architecture-freeze request.

## Context

The project has separate Auth and Tenant services. Registration and password login now use a global identity, while Tenant Service stores tenant memberships and roles. Earlier designs coupled accounts or JWTs to tenant context; that is no longer the intended contract.

## Problem Statement

The system needs one stable identity for a person who may belong to zero, one, or many tenants. Authentication must establish who the caller is without deciding which tenant they may access.

## Decision Drivers

- A global user must be able to register, verify email, and log in before joining a tenant.
- Identity identifiers must remain stable across tenant membership changes.
- Authentication and tenant authorization must have separate owners.
- Token consumers must validate signed identity assertions rather than trust client-supplied identity data.
- Subscription purchases must not change a person's identity or global role.

## Considered Options

1. **Tenant-scoped accounts and tokens:** rejected because users would need a tenant to authenticate and identities would be duplicated across tenants.
2. **Global identity with tenant and role claims in access tokens:** rejected as the source of tenant authorization because claims become stale when membership or role changes.
3. **Global identity token, with authorization delegated to the service that owns tenant membership:** selected.

## Decision

Auth Service is the sole owner of global identities and authentication. A verified identity can authenticate independently of tenant membership. Login accepts email and password only; it does not accept a tenant ID.

Auth issues RS256-signed access tokens. The immutable global user UUID is the JWT `sub`. Tenant IDs, tenant roles, and subscription entitlements are not identity claims and are not issued as authorization facts.

## Architecture and Responsibilities

| Concern | Owner | Decision |
|---|---|---|
| Global identity UUID, email, password credential, email verification, global account state | Auth Service | Auth-owned data; tenant services store UUID references without cross-service foreign keys. |
| Registration and authentication strategies | Auth Service | Email/password and configured external identity strategies authenticate a global identity. |
| JWT signing and JWKS publication | Auth Service | RS256 keys remain private to Auth; public signing keys are published from `/.well-known/jwks.json`. |
| Tenant membership, role, tenant state, subscription, and entitlement | Tenant Service | Never encoded as authoritative global identity roles. |
| Tenant-scoped business authorization | Tenant Service policy plus the calling business service | Tenant Service is authoritative for membership and entitlements; Order Service must enforce the result before data access. |

### JWT contract

| Claim/header | Meaning |
|---|---|
| `iss` | Configured Auth issuer. |
| `aud` | Intended API audience. |
| `sub` | Immutable global user UUID; the authoritative subject identifier. |
| `global_user_id` | Compatibility claim; must equal `sub` and must not override it. |
| `email` | Authenticated identity email; not a role or tenant selector. |
| `iat`, `nbf`, `exp` | Issue, not-before, and expiration times. |
| JOSE `alg`, `kid` | RS256 and the configured public-key identifier. |

Tokens must not contain `tid`, tenant roles, or tenant entitlements. A client-supplied tenant ID is only a request to operate in a selected tenant; it is never evidence of access.

## Detailed Workflow

1. A registrant submits email and password to Auth Service without a tenant ID.
2. Auth validates and hashes the credential, creates a pending global identity, and sends a one-time email challenge.
3. Auth verifies the challenge and activates the global identity.
4. The user logs in with email and password. Auth applies account-state and credential checks and issues a token with the global UUID as `sub`.
5. A consuming service validates signature, allowed algorithm, issuer, audience, expiry and not-before time, and validates that `sub` is a UUID. Tenant Service additionally requires `global_user_id` to equal `sub`.
6. The consuming service obtains current tenant authorization from Tenant Service; it does not infer it from token claims.

## Security Considerations

- Never log passwords, access tokens, verification codes, private keys, or invitation tokens.
- Consumers fail closed when signature, issuer, audience, or time validation fails. Key rotation must publish overlapping public keys under distinct `kid` values for the transition.
- JWTs are bearer credentials. Their limited lifetime reduces but does not eliminate the delay before an issued token expires after a global account is disabled.
- An external identity link must be derived from a cryptographically verified provider assertion. A caller-supplied provider subject or `emailVerified` boolean is not proof of control of that provider account.
- Rate limiting and account lockout remain Auth responsibilities; token issuance must not depend on Tenant Service availability.

## Failure Scenarios

- Invalid, unverified, disabled, locked, or incorrectly authenticated identities are rejected without disclosing whether an email is registered.
- An invalid or expired token is rejected by every service that consumes it.
- If JWKS cannot be obtained and no valid cached key is available, a consumer rejects the request; it must not accept an unverified token.
- Tenant Service outages do not prevent global login, but they prevent tenant-scoped operations that require current authorization.

## Consequences

### Positive

- Users can enter the product without creating or joining a tenant.
- One global UUID can be referenced by memberships in multiple tenants.
- Auth can evolve credentials and identity providers without owning tenant policy.

### Negative

- Tenant-scoped operations require a membership authorization path in addition to JWT validation.
- Stateless JWTs do not provide immediate global token revocation.

### Trade-offs

- `global_user_id` duplicates `sub` for compatibility; consumers must reject disagreement rather than select one arbitrarily.
- The architecture chooses online authorization for fresh tenant membership state over self-contained tenant permission claims.

## Implementation Status

**Partially aligned.** Auth registration, email verification, global email/password login, RS256 token issuance, and JWKS are implemented. `AccessTokenService` emits the global UUID in `sub` and `global_user_id`, with no tenant or role claims. Tenant Service validates signature, issuer, audience, time claims, and matching UUID claims.

Two gaps remain: Auth's own `JwtConfiguration.jwtDecoder()` does not configure explicit issuer/audience validators for protected JWT-consuming routes, and `GoogleIdentityController` accepts `providerSubject` and `emailVerified` from the request instead of deriving them from a verified Google assertion. No code change is included in this documentation decision.

## Validation and Acceptance Criteria

- Registration and login requests contain no tenant ID.
- A verified global identity with no memberships can obtain a token.
- `sub` equals the Auth-owned UUID; tokens contain no tenant ID, tenant role, or entitlement claims.
- Tenant Service rejects incorrect signature, issuer, audience, time claims, malformed UUID subjects, and disagreement between `sub` and `global_user_id`.
- Protected Auth endpoints validate the same issuer/audience contract before trusting a JWT.
- External identity linking accepts only a verified provider subject.
- Tests cover account verification/status, credential rejection, and authentication independent of membership.

## References

- [AuthController](../../services/auth-service/src/main/java/com/authservice/api/AuthController.java)
- [PasswordAuthenticationProvider](../../services/auth-service/src/main/java/com/authservice/security/PasswordAuthenticationProvider.java)
- [AccessTokenService](../../services/auth-service/src/main/java/com/authservice/service/AccessTokenService.java)
- [JwtConfiguration](../../services/auth-service/src/main/java/com/authservice/config/JwtConfiguration.java)
- [GoogleIdentityController](../../services/auth-service/src/main/java/com/authservice/api/GoogleIdentityController.java)
- [Tenant JWT validation](../../services/tenant-service/src/main/java/com/tenant/service/config/JwtSecurityConfiguration.java)
- [ADR-0005: Global Auth and Tenant Data Boundary](./ADR-0005-global-auth-and-tenant-data-boundary.md)
- Project blueprint supplied with the task (external DOCX source).

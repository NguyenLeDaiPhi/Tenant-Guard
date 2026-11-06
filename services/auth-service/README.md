# Auth Service

The Authentication Service (`auth-service`) manages tenant-scoped user accounts, administrator-provisioned memberships, email-verified public registration, credential verification, external identity mapping (Google OIDC), and short-lived RS256 JWT access token issuance for the TenantGuard OMS platform.

It is implemented with **Spring Boot 4 / Spring Security 7 on Java 27**, aligning with the documented microservice boundaries and least-privilege PostgreSQL database isolation.

---

## 1. Architectural Role and Service Boundaries

- **Database Ownership**:
  - `auth-service` owns `public.users`, `public.tenant_memberships`, `public.email_verification_codes`, `public.auth_rate_limits`, and `public.external_identities`.
  - `tenant-service` owns `public.tenants` and provisions tenant schemas (`tenant_{id}`).
  - `auth-service` has **read-only** visibility into `public.tenants.status` via a JDBC repository. It does not map `public.tenants` as a Hibernate entity, so Hibernate schema validation does not require the other service's table to be present during Auth Service startup.
- **Zero Lateral Coupling & Stateless Verification**:
  - Downstream services (`order-service`, `tenant-service`) do not call `auth-service` synchronously at request time.
  - Access tokens are signed using **RS256** with an RSA 2048-bit private key.
  - Public keys are published in RFC 7517 format at `/.well-known/jwks.json`.
  - Downstream services validate tokens statelessly and extract `sub` (user UUID), `role`, and `tid` (tenant ID).
- **Multi-Tenant Identity Scoping**:
  - `public.user_identities` stores globally unique email identities and verification state. It does not create an authenticated principal or tenant access.
  - `PLATFORM_ADMIN`: Global operator; `tenant_id` is `null`; barred from tenant order data.
  - Tenant Roles (`TENANT_ADMIN`, `ORDER_MANAGER`, `ORDER_VIEWER`): Strictly bound to a non-null `tenant_id`.

---

## 2. Authentication Strategies & Design Patterns

### The Strategy Design Pattern
Compatible credential-verification workflows use the Strategy Pattern:
- **`AuthenticationStrategy`**: Domain interface defining `getMethod()` and `authenticate(AuthenticationRequest)`.
- **`PasswordAuthenticationStrategy`**: Concrete strategy that translates requests into Spring Security's `PasswordAuthenticationToken` and delegates to `AuthenticationManager`.
- **`AuthenticationStrategyResolver`**: Spring component collecting all `AuthenticationStrategy` beans via dependency injection, indexing them in an immutable map, and rejecting duplicate strategies at application startup.
- **Decoupling**: Strategy selection is isolated from token issuance (`AccessTokenService`) and account management (`RegistrationService`). Adding future compatible strategies (such as machine-to-machine API keys) requires only a new strategy bean without modifying existing callers.

### Why Google OIDC is NOT a Synchronous Strategy
Google OAuth2 / OpenID Connect is an asynchronous, browser-redirect authorization code flow. Forcing OIDC into a synchronous password-style interface would introduce artificial blocking or pseudo-credentials. Instead, Spring Security's native `OAuth2LoginAuthenticationFilter` handles the handshake, while `GoogleOidcAuthenticationHandler` and `ExternalIdentityService` manage user account resolution and token issuance.

### Strict External Identity Rules
- Accounts are **never** automatically linked to Google identities solely by matching email addresses.
- External identities are mapped by immutable `(provider, provider_subject)` in `public.external_identities`.
- Users must explicitly link their external identity to their authenticated account.
- Linking is idempotent: re-linking the same provider subject to the same user account succeeds cleanly. Attempting to link a subject already claimed by another account returns `409 Conflict`.

---

## 3. Account Security Policies

- **Email Normalization**: Raw email addresses are trimmed and converted to lowercase using Java `Locale.ROOT`. No provider-specific dot or plus-address rewriting is applied.
- **Password Security**:
  - BCrypt hashing with salt. Plaintext passwords are never persisted or logged.
  - Password length: minimum 12 characters, maximum 72 bytes (BCrypt truncation threshold).
- **Account Lockout**:
  - Ten consecutive failed login attempts trigger an automatic 15-minute lockout (`locked_until`).
  - Lockout state is cleared automatically upon expiration or on successful login.
- **Tenant Status Guard**: Authentication requires the tenant status in `public.tenants` to be `ACTIVE`. Inactive or suspended tenants are rejected.
- **Anti-Enumeration Responses**:
  - Invalid credentials, locked accounts, disabled accounts, and inactive tenants return a uniform `401 Unauthorized` with the generic message `"Invalid credentials or account unavailable"`.

## 4. Local PostgreSQL tenant registry bootstrap

The Auth Service Flyway migrations intentionally do not create `public.tenants`: tenant metadata belongs to Tenant Service, and no Tenant Service implementation or migration exists in this repository yet. The container logs showed the actual startup defect: Hibernate tried to validate `public.tenants` because `TenantStatus` was annotated as a JPA entity, while the Auth Service migrations created only auth-owned tables.

The fix separates the concerns:

- `TenantStatus` is now a plain read model, and `TenantStatusRepository` uses JDBC to read `public.tenants`; Hibernate no longer validates another service's table.
- `tenant-schema-bootstrap` in the local Compose stack idempotently creates the UUID-based tenant registry before Auth Service starts. The table is owned by a non-login `tenant_service` database role; the separate, non-superuser Auth Service login receives `SELECT` access. This is a local-development stand-in for the missing Tenant Service migration, not an Auth Service migration.
- Auth Service Flyway history is stored in the dedicated `auth_service` schema, separately from Tenant Service's `public.tenants` table. The bootstrap creates that schema and, for an existing local database, moves the existing Auth Flyway history table into it without resetting or recreating migration records.
- Auth Service runs a startup preflight that checks the registry table is readable and that its `id` column is UUID. If the owner migration has not run, startup now reports this prerequisite directly instead of failing later during Hibernate validation or a login request.
- The bootstrap can run against an existing PostgreSQL volume and does not delete or reset it. It does not change an already-existing table's schema. If `public.tenants` already exists with a different ID type or incompatible columns, migrate it through Tenant Service rather than dropping it.

Compose requires a root `.env` file containing the database passwords and JWT keys. Compose reads `.env` for interpolation but passes only the explicitly mapped values into each container. The bootstrap receives `POSTGRES_USER`/`POSTGRES_PASSWORD` as PostgreSQL administrator credentials and creates or updates the restricted `DB_USERNAME` role with `DB_PASSWORD`. Inside the bootstrap container, `PGPASSWORD` is mapped from `POSTGRES_PASSWORD` and `AUTH_DB_PASSWORD` is mapped from `DB_PASSWORD`; these are internal aliases, not separate values to add to `.env`. The bootstrap transfers ownership of existing Auth Service tables to the restricted login so migrations can run without the application using the PostgreSQL administrator. Never put credentials or signing keys in tracked files. Any signing key previously committed in Compose must be treated as compromised and rotated before use.

`POSTGRES_USER` defaults to `auth_service` for compatibility with the current local database setup. For an existing `pgdata` volume, `POSTGRES_PASSWORD` must match the administrator password with which that volume was initialized; changing the environment variable does not change a persisted PostgreSQL role password. The bootstrap creates/updates the separate `DB_USERNAME` role and transfers existing Auth Service table ownership to it.

Create a local environment file, set database passwords, and generate a fresh JWT key pair in PowerShell 7 (the key loader expects Base64-encoded DER bytes, not PEM text):

```powershell
Copy-Item .env.example .env
notepad .env

$rsa = [System.Security.Cryptography.RSA]::Create(2048)
$privateKey = [Convert]::ToBase64String($rsa.ExportPkcs8PrivateKey())
$publicKey = [Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo())
$rsa.Dispose()
$envLines = Get-Content .env
$envLines = $envLines | ForEach-Object {
    if ($_ -match '^AUTH_JWT_PRIVATE_KEY_BASE64=') { "AUTH_JWT_PRIVATE_KEY_BASE64=$privateKey" }
    elseif ($_ -match '^AUTH_JWT_PUBLIC_KEY_BASE64=') { "AUTH_JWT_PUBLIC_KEY_BASE64=$publicKey" }
    else { $_ }
}
Set-Content -Path .env -Value $envLines -Encoding utf8NoBOM
```

In `.env`, set distinct strong values for `POSTGRES_PASSWORD` and `DB_PASSWORD`. When reusing an existing PostgreSQL volume, `POSTGRES_PASSWORD` must be the current password for `POSTGRES_USER`; changing it in `.env` does not update the persisted PostgreSQL role. The checked-in example contains only non-secret local defaults; change the issuer, audience, key ID, and token TTL only if your local setup needs different values. Keep `.env` private and do not send its contents to logs or source control. The RSA private key is PKCS#8 DER before Base64 encoding; the public key is X.509 SubjectPublicKeyInfo DER before Base64 encoding. The Java configuration validates a minimum 2048-bit RSA modulus and matching public/private moduli, and token issuance explicitly uses RS256.

---

## 4a. Public registration and verified identities

The public registration request contains only an email and password. It creates a global pending identity without requiring a tenant membership; the identity is not a tenant account and cannot authenticate to tenant resources. Only the email owner can change it to `VERIFIED` by submitting the one-time code.

Verification codes contain 256 bits of randomness, are stored as SHA-256 hashes, expire after 15 minutes, and are single-use with a five-attempt limit. Re-registering an unverified identity changes its pending password and invalidates prior codes. Re-registering an already verified identity or an existing active tenant account does not change credentials or issue another code. Registration and verification use database-backed fixed-window limits: five requests/hour per IP and normalized email for registration, and ten/hour for verification. IP throttling uses the servlet peer address; deployments behind a proxy must only enable forwarded-header handling for trusted proxies.

Email verification marks only the global identity as verified. It never creates or activates a tenant account or membership. A tenant administrator must explicitly provision the verified identity through `POST /api/auth/memberships`; the tenant ID and role come from that authenticated administrator request. A verified identity without a membership has no tenant-scoped login or access token. If an administrator provisioned a pending membership before verification, the administrator repeats the same request after verification to complete activation.

The database transaction that stores an identity/challenge commits before synchronous SMTP delivery, so database locks are not held open while contacting the mail server. SMTP and PostgreSQL are not atomic: delivery failure is reported as an error while the pending identity and hashed challenge remain; repeating registration rotates the code and retries. Expired and consumed challenge records older than 30 days are removed periodically. Local Compose runs Mailpit as an SMTP capture service at `http://localhost:8025`; production must configure a real SMTP host, credentials, sender, and TLS policy through deployment secrets.

Flyway V3 adds memberships, verification-code and rate-limit tables, and migrates unexpired legacy invitation rows into pending memberships. V4 adds `public.user_identities`, links new verification challenges and tenant accounts to those identities, and does not rewrite existing users, memberships, invitations, or challenges. Legacy pending account rows remain; after verifying the email as an identity, an authorized tenant administrator can repeat provisioning to complete the existing pending membership.

```mermaid
sequenceDiagram
  actor Admin as Tenant administrator
  actor User as Registrant
  participant Auth as Auth Service
  participant DB as Auth-owned PostgreSQL schema
  participant SMTP as Configured SMTP server
  Admin->>Auth: POST /api/auth/memberships (JWT, tenant, email, role)
  Auth->>Auth: Verify TENANT_ADMIN and JWT tenant equals requested tenant
  Auth->>DB: Store pending membership
  User->>Auth: POST /api/auth/register (email, password only)
  Auth->>DB: Create/reuse PENDING_EMAIL global identity
  Auth->>DB: Store code hash and expiry, invalidate prior challenge
  Auth->>DB: Commit identity and challenge
  Auth->>SMTP: Send one-time code after commit
  Auth-->>User: 202 generic accepted response
  User->>Auth: POST /api/auth/verify-email (email, code)
  Auth->>DB: Check rate limit, expiry, attempt count; consume code
  Auth->>DB: Mark global identity VERIFIED only
  Auth-->>User: 204 No Content
  Admin->>Auth: Provision membership after identity verification
  Auth->>DB: Create ACTIVE tenant account from trusted admin membership and verified identity
```

## 5. HTTP API Contract

### Public Endpoints

#### `POST /api/auth/register`
Publicly starts email verification without requiring a tenant membership. The request has no invitation token, tenant ID, role, or authorization header. New requests create a pending global identity; repeated requests for a pending identity update its password, invalidate the old code, and send a fresh code. Existing verified identities and existing active accounts are not modified; the generic response is retained to avoid exposing account state.
```json
{
  "email": "user@example.com",
  "password": "SecurePassword123!"
}
```
**Response `202 Accepted`** (generic response for eligible and ineligible email/account states):
```json
{
  "message": "If this email is eligible, a verification code will be sent."
}
```

#### `POST /api/auth/verify-email`
Publicly verifies the one-time code sent to the email address. A valid code is single-use, expires after 15 minutes, and marks only the global identity as verified. It does not create a tenant account or activate any membership. Returns `204 No Content`; invalid, expired, or exhausted codes return `400 Bad Request`.
```json
{
  "email": "user@example.com",
  "code": "<64-character-hex-code-from-email>"
}
```

#### `POST /api/auth/login`
Authenticates via email and password, returning an RS256 JWT access token.
```json
{
  "tenantId": "11111111-1111-4111-8111-111111111111",
  "email": "user@example.com",
  "password": "SecurePassword123!"
}
```
*Note: `tenantId` is omitted only for `PLATFORM_ADMIN`.*

**Response `200 OK`**:
```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIs...",
  "tokenType": "Bearer",
  "expiresIn": 600
}
```

#### `GET /.well-known/jwks.json`
Publishes the RSA public verification key set in RFC 7517 format for downstream services.

---

### Authenticated Endpoints (Requires Bearer JWT)

#### `POST /api/auth/memberships`
Only a tenant administrator may provision a membership for the tenant in their JWT; platform-admin and cross-tenant requests are rejected. The role and tenant are trusted administrator inputs and are never accepted by public registration. If the identity has already verified its email, the administrator's provisioning request creates an active tenant account and membership. Otherwise it creates a pending membership. If a pending membership already exists, the same administrator can repeat the request after email verification to complete provisioning; the role must match the pending membership.
```json
{
  "tenantId": "11111111-1111-4111-8111-111111111111",
  "email": "colleague@example.com",
  "role": "ORDER_MANAGER"
}
```
**Response `201 Created`** (status is `ACTIVE` when identity is verified; otherwise `PENDING`):
```json
{
  "membershipId": "550e8400-e29b-41d4-a716-446655440000",
  "tenantId": "11111111-1111-4111-8111-111111111111",
  "email": "colleague@example.com",
  "role": "ORDER_MANAGER",
  "status": "ACTIVE"
}
```

`status` is `PENDING` until both the email is verified and a tenant administrator provisions the membership. An identity without a membership cannot log in to tenant resources or receive a tenant-scoped JWT. This repository does not currently contain a tenant invitation-acceptance API or tenant-creation workflow; onboarding remains administrator-provisioned.

#### `POST /api/auth/external-identities/google`
Explicitly binds the authenticated user account to a verified Google subject identifier.
```json
{
  "providerSubject": "google-sub-1029384756",
  "emailVerified": true
}
```
**Statuses**: `201 Created`, `200 OK` (idempotent), `409 Conflict` (already claimed by another account).

---

## 6. Environment Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8081` | Service HTTP port |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/tenantguard` | PostgreSQL JDBC URL |
| `POSTGRES_USER` | `auth_service` (Compose) | PostgreSQL administrator login used by the bootstrap |
| `POSTGRES_PASSWORD` | required | PostgreSQL administrator password |
| `DB_USERNAME` | `auth_service_app` (Compose), `auth_service` (standalone) | Auth Service database login |
| `DB_PASSWORD` | required in Compose | Auth Service database password |
| `AUTH_JWT_ISSUER` | `http://localhost:8081` | JWT `iss` claim |
| `AUTH_JWT_AUDIENCE` | `tenantguard-api` | JWT `aud` claim |
| `AUTH_JWT_ACCESS_TOKEN_TTL` | `PT10M` | Token lifetime (default 10 minutes) |
| `AUTH_JWT_KEY_ID` | `tenantguard-local` | JWS Header `kid` |
| `AUTH_JWT_PRIVATE_KEY_BASE64` | required | Base64-encoded PKCS#8 DER private key (min 2048-bit) |
| `AUTH_JWT_PUBLIC_KEY_BASE64` | required | Base64-encoded X.509 DER public key (min 2048-bit) |
| `MAIL_HOST` | `mailpit` in Compose | SMTP server hostname |
| `MAIL_PORT` | `1025` in Compose | SMTP server port |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | empty for local Mailpit | SMTP credentials; use deployment secrets in production |
| `MAIL_SMTP_AUTH` | `false` in Compose | Enable SMTP authentication |
| `MAIL_SMTP_STARTTLS` | `false` in Compose | Enable SMTP STARTTLS; configure for the production provider |
| `MAIL_FROM` | `no-reply@tenantguard.local` | Verification sender address |

---

## 7. Local Development & Testing

### Running Tests
Execute the automated test suite from the repository root:
```powershell
.\services\auth-service\mvnw.cmd -f .\services\auth-service\pom.xml test
```
The test suite includes:
- `AuthenticationStrategyResolverTests`: Strategy registration and duplicate rejection.
- `PasswordAuthenticationProviderTests`: BCrypt verification, lockout counter, account status checks, and inactive tenant rejection.
- `JwtTokenTests`: RS256 token issuance, claims verification, and signature tampering rejection.
- `ExternalIdentityServiceTests`: Google subject mapping, conflict prevention, idempotent re-linking, and anti-email-auto-linking verification.
- `RegistrationServiceTests`, `EmailVerificationServiceTests`, and `MembershipServiceTests`: pending account creation, BCrypt storage, single-use verification, and tenant-admin assignment boundaries.
- `AuthServiceApplicationTests`: End-to-end Testcontainers integration tests (automatically skipped if Docker daemon is not active).

### Running with Docker Compose
From the repository root, copy `.env.example` to `.env`, set both database passwords, and provide the generated RSA keys as described above. Then validate and start the stack:
```bash
docker compose config --quiet
docker compose up --build
```

In another terminal, inspect status and follow each service's startup logs (press Ctrl+C to stop following a log):
```bash
docker compose ps
docker compose logs -f postgres
docker compose logs -f tenant-schema-bootstrap
docker compose logs -f auth-service
docker compose logs -f mailpit
```

The local bootstrap creates an empty tenant registry; it does not seed tenants or tenant administrators. Users can request email verification without a membership, but a tenant administrator must provision a membership before they can access tenant features. View captured local verification mail at `http://localhost:8025`. Check the health and JWKS endpoints after Auth Service starts:
```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8081/.well-known/jwks.json
```

#### Bootstrapping the first local tenant administrator

The tenant owner must first create an active tenant in `public.tenants`; Auth Service does not create or modify tenant registry data. For local development only, Auth Service can create the first `TENANT_ADMIN` for an already active tenant when explicitly enabled. Set these environment variables for Auth Service:

```text
AUTH_BOOTSTRAP_TENANT_ADMIN_ENABLED=true
AUTH_BOOTSTRAP_TENANT_ID=<UUID of an existing ACTIVE tenant>
AUTH_BOOTSTRAP_ADMIN_EMAIL=admin@example.com
AUTH_BOOTSTRAP_ADMIN_PASSWORD=<unique strong password>
```

On startup, Auth Service validates the tenant is active, serializes concurrent bootstrap attempts, and creates the admin only when no tenant administrator exists for that tenant. The password is BCrypt-hashed. If an administrator already exists, startup does not change its credentials. Disable the bootstrap and remove its password from the environment immediately after the account is created; do not enable this mechanism in production.

The administrator can then log in and call `POST /api/auth/memberships` for each normalized registrant email and allowed tenant role. Public registration creates a pending global identity, persists only the hash of a one-time verification code, and sends the verification email. Email verification alone grants no tenant access. Do not send tenant IDs or roles in the public registration request.

Verification challenge and identity writes commit before SMTP delivery begins, so no database row locks are held while waiting for the mail server. SMTP and PostgreSQL cannot commit atomically: if delivery fails, the API reports an error while the pending identity and hashed challenge remain. Submitting registration again rotates the pending challenge and retries email delivery. Expired and consumed challenge records older than 30 days are removed periodically.

### Deploying to Kubernetes
Apply the Kubernetes manifests from `k8s/base/auth-service`:
```bash
kubectl apply -k k8s/base/auth-service
```
This deploys 2 replicas under UID `10001` in the `tenantguard-app` namespace with NGINX Ingress rules, liveness/readiness probes, and resource constraints.

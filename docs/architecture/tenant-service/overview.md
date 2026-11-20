# Tenant Service Architecture

## Ownership and trust boundary

Auth Service owns global identities and credentials. It issues RS256 JWTs with the global identity UUID in both `sub` and `global_user_id`, and no tenant claims. Tenant Service trusts only JWTs validated against Auth's JWKS, configured issuer, audience, temporal claims, and UUID subject. Tenant membership and authorization are resolved only from the `tenant_service` schema.

Tenant Service owns all tenant, membership, invitation, subscription, and entitlement tables under `tenant_service`. The legacy `public.tenants` registry is retained as `tenant_service.legacy_tenant_registry`. Auth no longer reads tenant data, and there are no foreign keys from Tenant Service to Auth tables. A separate migration role has limited read access to selected legacy Auth tables for the one-time transfer; the Tenant Service runtime role does not.

## Tenant creation

```mermaid
sequenceDiagram
    participant Client
    participant Auth as Auth JWKS
    participant Security as Tenant JWT Resource Server
    participant API as Tenant API
    participant DB as PostgreSQL
    Client->>Security: Bearer RS256 JWT
    Security->>Auth: Fetch public JWK set as needed
    Auth-->>Security: Public signing key
    Security->>Security: Validate signature, issuer, audience, time, sub and global_user_id
    Client->>API: POST /api/v1/tenants
    API->>DB: Lock and revalidate active plan/current published version
    API->>DB: Insert tenant, OWNER membership, subscription and entitlement snapshots
    DB-->>API: Commit atomically
    API-->>Client: 201 Created
```

## Subscription selection

```mermaid
sequenceDiagram
    participant Client
    participant API as Tenant Service
    participant DB as tenant_service schema
    Client->>API: GET /api/v1/subscription-plans with Auth JWT
    API->>DB: Read active plans/current published versions/typed entitlements
    DB-->>API: Catalogue rows
    API-->>Client: Plan DTOs generated from database
    Client->>API: POST /api/v1/tenants with plan code
    API->>DB: Lock active plan and current published version
    API->>DB: Persist selected version and entitlement snapshot
    Note over API,DB: Zero-price plan is ACTIVE; other plans are PENDING_PAYMENT
```

## Membership authorization

```mermaid
sequenceDiagram
    participant Caller
    participant Security as JWT Resource Server
    participant Service as Tenant Authorization
    participant DB as tenant_service
    Caller->>Security: Signed JWT
    Security->>Security: Validate issuer, audience, signature, expiry, UUID claims
    Security->>Service: global_user_id
    Service->>DB: Find active tenant membership
    DB-->>Service: Membership role/status
    Service->>Service: Require MEMBER access or OWNER for update
    Service-->>Caller: Authorized response or hidden 404 / 403
```

## ERD

```mermaid
erDiagram
    TENANT ||--o{ TENANT_MEMBERSHIP : includes
    TENANT ||--|| TENANT_SUBSCRIPTION : selects
    SUBSCRIPTION_PLAN ||--o{ PLAN_VERSION : versions
    PLAN_VERSION ||--o{ PLAN_ENTITLEMENT : defines
    PLAN_VERSION ||--o{ TENANT_SUBSCRIPTION : selected_by
    TENANT_SUBSCRIPTION ||--o{ SUBSCRIPTION_ENTITLEMENT : snapshots
    TENANT {
      UUID id PK
      string name
      string slug UK
      UUID owner_user_id
      string status
    }
    TENANT_MEMBERSHIP {
      UUID id PK
      UUID tenant_id FK
      UUID user_id
      string role
      string status
      boolean is_owner
    }
    SUBSCRIPTION_PLAN {
      UUID id PK
      string code UK
      boolean active
    }
    PLAN_VERSION {
      UUID id PK
      UUID plan_id FK
      int version
      bigint price_minor
      string publication_status
    }
    PLAN_ENTITLEMENT {
      UUID id PK
      UUID plan_version_id FK
      string entitlement_key
      string value_type
    }
    TENANT_SUBSCRIPTION {
      UUID id PK
      UUID tenant_id FK
      UUID plan_version_id FK
      string status
    }
    SUBSCRIPTION_ENTITLEMENT {
      UUID id PK
      UUID subscription_id FK
      string entitlement_key
      string value_type
    }
```

## Class responsibilities

```mermaid
classDiagram
    class TenantController
    class SubscriptionPlanController
    class CurrentUserProvider
    class TenantAuthorizationService
    class TenantApplicationService
    class SubscriptionPlanService
    class TenantRepository
    class TenantMembershipRepository
    class SubscriptionPlanRepository
    class PlanVersionRepository
    class TenantSubscriptionRepository
    class SubscriptionEntitlementRepository
    TenantController --> CurrentUserProvider
    TenantController --> TenantApplicationService
    SubscriptionPlanController --> SubscriptionPlanService
    TenantApplicationService --> TenantAuthorizationService
    TenantApplicationService --> SubscriptionPlanService
    TenantApplicationService --> TenantRepository
    TenantApplicationService --> TenantMembershipRepository
    TenantApplicationService --> TenantSubscriptionRepository
    TenantApplicationService --> SubscriptionEntitlementRepository
    SubscriptionPlanService --> SubscriptionPlanRepository
    SubscriptionPlanService --> PlanVersionRepository
```

## JWT authentication flow

```mermaid
sequenceDiagram
    participant User
    participant Auth as Auth Service
    participant Tenant as Tenant Service
    User->>Auth: Login
    Auth->>Auth: Sign RS256 JWT (sub and global_user_id are the global identity UUID)
    Auth-->>User: Access token
    User->>Tenant: API request with Bearer token
    Tenant->>Auth: GET /.well-known/jwks.json when key cache requires
    Auth-->>Tenant: RSA public JWK
    Tenant->>Tenant: Verify RS256, issuer, audience, exp, nbf, subject and global_user_id
    Tenant->>Tenant: Authorize using tenant_service membership rows
    Tenant-->>User: Response
```

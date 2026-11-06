# TenantGuard OMS - Mermaid Diagrams

## 1. System Context (C4 Level 1)

```mermaid
C4Context
  title System Context Diagram - TenantGuard OMS (C4 Level 1)

  Person(tenantUser, "Tenant User", "Staff of a customer organization (tenant). Creates, reads, updates and cancels only that tenant's orders.")
  Person(platformAdmin, "Platform Administrator", "Platform operator. Creates, lists and deactivates tenants. Holds no tenant claim, so order data is out of reach.")

  System_Boundary(oms_boundary, "TenantGuard OMS system boundary") {
    System(oms, "TenantGuard OMS", "Local-first multi-tenant Order Management System. Auth, Tenant and Order services (Java / Spring Boot) behind an NGINX Ingress Controller on Kubernetes.")
  }

  SystemDb_Ext(pg, "PostgreSQL Database", "Shared instance. Global public schema plus one isolated tenant_{id} schema per tenant.")

  Rel(tenantUser, oms, "Logs in and manages own-tenant orders", "HTTPS 443, REST/JSON, Bearer JWT")
  Rel(platformAdmin, oms, "Administers tenant lifecycle", "HTTPS 443, REST/JSON, Bearer JWT")
  Rel(oms, pg, "Reads and writes platform metadata and tenant order data", "TCP 5432, SQL over JDBC")

  UpdateLayoutConfig($c4ShapeInRow="2", $c4BoundaryInRow="1")
```

## 2. Container and Service Architecture (C4 Level 2)

```mermaid
flowchart LR
  classDef actor fill:#08427b,stroke:#052e56,color:#ffffff
  classDef edge fill:#6b4fbb,stroke:#45308a,color:#ffffff
  classDef svc fill:#1168bd,stroke:#0b4884,color:#ffffff
  classDef db fill:#2e7d32,stroke:#1b5e20,color:#ffffff
  classDef stretch fill:#f5f5f5,stroke:#757575,stroke-width:2px,stroke-dasharray:6 4,color:#424242

  TU(["Tenant User<br/>Person"]):::actor
  PA(["Platform Administrator<br/>Person"]):::actor

  subgraph K8S["Kubernetes cluster (kind / Minikube)"]
    subgraph NS_ING["Namespace: ingress-nginx"]
      NGINX["NGINX Ingress Controller<br/>[Container: ingress-nginx]<br/>TLS termination, path routing<br/>:443 HTTPS / :80 redirect"]:::edge
    end

    subgraph NS_APP["Namespace: tenantguard-app (service boundary: one Deployment per service)"]
      AUTH["Auth Service<br/>[Container: Spring Boot]<br/>Login, password hashing, JWT issue<br/>app :8081 / mgmt :9090"]:::svc
      TEN["Tenant Service<br/>[Container: Spring Boot]<br/>Tenant lifecycle, schema provisioning<br/>app :8082 / mgmt :9090"]:::svc
      ORD["Order Service<br/>[Container: Spring Boot]<br/>Order CRUD, tenant schema routing<br/>app :8083 / mgmt :9090"]:::svc
    end

    subgraph NS_DATA["Namespace: tenantguard-data"]
      PG[("PostgreSQL<br/>[Container: StatefulSet]<br/>public + tenant_{id} schemas<br/>:5432")]:::db
    end

    subgraph STRETCH["STRETCH / FUTURE GOALS (not in core milestone)"]
      ISTIO["Istio Gateway<br/>[STRETCH - Tier 4]<br/>Alternative entry point,<br/>STRICT mTLS between pods"]:::stretch
      VAULT["HashiCorp Vault<br/>[STRETCH - Tier 4]<br/>Secret retrieval,<br/>short-lived DB credentials"]:::stretch
      OTEL["OpenTelemetry Collector<br/>[STRETCH - Tier 3]<br/>Distributed traces<br/>OTLP gRPC :4317"]:::stretch
    end
  end

  TU -->|"HTTPS :443"| NGINX
  PA -->|"HTTPS :443"| NGINX

  NGINX -->|"/api/auth/* : HTTP :8081"| AUTH
  NGINX -->|"/api/tenants/* : HTTP :8082"| TEN
  NGINX -->|"/api/orders/* : HTTP :8083"| ORD

  AUTH -->|"JDBC TCP :5432 / role auth_svc / public.users"| PG
  TEN -->|"JDBC TCP :5432 / role tenant_svc / public.tenants + CREATE SCHEMA"| PG
  ORD -->|"JDBC TCP :5432 / role order_svc / public.tenants read + tenant_{id}.*"| PG

  ISTIO -.->|"alt. L7 entry, ADR required"| NS_APP
  VAULT -.->|"inject secrets"| NS_APP
  VAULT -.->|"dynamic credentials"| PG
  NS_APP -.->|"OTLP traces"| OTEL
```

## 3. Request-Flow Sequence (Order Creation)

```mermaid
sequenceDiagram
  autonumber
  actor C as Client (Tenant User)
  participant I as NGINX Ingress :443
  box rgb(232,240,254) Order Service Pod :8083
    participant F as Servlet Filter Chain
    participant R as TenantRegistry
    participant S as OrderService
    participant P as TenantConnectionProvider
  end
  participant D as PostgreSQL :5432

  C->>I: POST /api/orders (HTTPS, Authorization Bearer JWT, Idempotency-Key)
  I->>I: TLS termination, route /api/orders to order-service:8083, add X-Request-ID
  I->>F: HTTP :8083 (plaintext in-cluster, mTLS is a stretch goal)

  Note over F: RequestIdFilter binds correlation ID to logging context
  F->>F: JwtAuthenticationFilter verifies signature (RS256), iss, aud, exp, nbf

  alt Token missing, malformed, bad signature or expired
    rect rgb(255,230,230)
      F-->>I: 401 Unauthorized, WWW-Authenticate Bearer error invalid_token
      I-->>C: 401 (no DB access, no tenant detail in body)
    end
  else Token valid
    F->>F: TenantResolutionFilter reads tenant claim tid from the validated JWT only
    F->>F: Compare with any client-supplied tenant_id in query, header or body

    alt Client tenant value differs from token claim (cross-tenant tampering)
      rect rgb(255,230,230)
        F-->>I: 403 Forbidden TENANT_MISMATCH (audit log, security metric incremented)
        I-->>C: 403
      end
    else No override or value equals token claim
      F->>R: resolve(tid)
      R-->>F: schema_name and status (cache hit, else SELECT on public.tenants)

      alt Tenant unknown or deactivated
        rect rgb(255,230,230)
          F-->>C: 403 TENANT_INACTIVE
        end
      else Tenant active
        F->>F: Method security checks role ORDER_WRITE (else 403)
        F->>S: createOrder(command) with TenantContext set

        S->>P: begin transaction, acquire connection for tenant
        P->>D: SET search_path TO tenant_1001, public
        Note over P,D: Schema name comes from the registry, validated against ^tenant_[0-9]+$ and quoted

        S->>D: INSERT INTO idempotency_keys (key, request_hash) ON CONFLICT DO NOTHING
        alt Key already stored (retry or double submit)
          D-->>S: 0 rows inserted
          S->>D: ROLLBACK
          S-->>C: 200 OK, original response replayed (409 if payload hash differs)
        else New key
          S->>D: INSERT INTO orders and order_items (resolved inside tenant_1001)
          D-->>S: generated order id
          S->>D: COMMIT
        end

        P->>D: RESET search_path (connection returned to pool clean)
        S-->>F: OrderResponse
        F-->>I: 201 Created, Location header, X-Request-ID
        I-->>C: 201 Created
      end
    end
  end

  rect rgb(255,243,224)
    Note over C,D: Variant - read of another tenant's order by guessed ID
    C->>I: GET /api/orders/{tenant B order id} with Tenant A token
    I->>F: forwarded, JWT valid, tid = 1001
    F->>S: getOrder(id) with TenantContext 1001
    S->>D: SET search_path TO tenant_1001, public, then SELECT FROM orders WHERE id = ?
    D-->>S: 0 rows (the row lives in tenant_1002 and is not visible)
    S-->>C: 404 ORDER_NOT_FOUND (same response as a non-existent ID, no existence leak)
  end
```

## 3a. Public Registration and Verified Tenant Membership

Tenant membership is provisioned by an authenticated tenant administrator in Auth Service. Tenant ID and role used by public registration come exclusively from the pending membership record. The registration request accepts only email and password; its account remains unable to authenticate until the email code is verified.

```mermaid
sequenceDiagram
  autonumber
  actor A as Tenant administrator
  actor U as Registrant
  participant AUTH as Auth Service
  participant DB as Auth-owned PostgreSQL tables
  participant SMTP as Configured SMTP server

  A->>AUTH: POST /api/auth/memberships with tenant JWT
  AUTH->>AUTH: Require TENANT_ADMIN and matching JWT tid
  AUTH->>DB: Store pending tenant/email/role membership
  U->>AUTH: POST /api/auth/register with email/password only
  AUTH->>DB: Lock matching memberships; create PENDING_EMAIL account(s)
  AUTH->>DB: Store SHA-256 hash of random code, expiry, attempt count
  AUTH->>SMTP: Send code to normalized email
  AUTH-->>U: 202 generic response (no membership disclosure)
  U->>AUTH: POST /api/auth/verify-email with email/code/chosen password
  AUTH->>DB: Enforce rate limit; validate expiry and single use
  AUTH->>DB: Atomically activate associated accounts and memberships
  AUTH-->>U: 204 No Content
```

Registration and verification share database-backed fixed-window rate limits. The code is cryptographically random, expires after 15 minutes, is stored hashed, and is invalidated on use or after five failed attempts. Local Compose uses Mailpit as an SMTP capture service; production supplies a real SMTP configuration.

## 4. Multi-Tenant Data Isolation

```mermaid
flowchart TB
  classDef ctx fill:#e8f0fe,stroke:#1a56db,color:#0b2a6f
  classDef pub fill:#fff8e1,stroke:#b28704,color:#5f4500
  classDef ta fill:#e8f5e9,stroke:#2e7d32,color:#1b4d1f
  classDef tb fill:#fce4ec,stroke:#ad1457,color:#6a0a35
  classDef pool fill:#ede7f6,stroke:#5e35b1,color:#2d1a63

  REQ(["Request with validated JWT<br/>claim tid = 1001 (Tenant A)"]):::ctx

  subgraph APP["Order Service Pod (server-side runtime)"]
    TC["TenantContext<br/>tenantId = 1001 (from JWT, never from client input)"]:::ctx
    REG["TenantRegistry lookup<br/>schema_name = tenant_1001<br/>regex ^tenant_[0-9]+$, status = ACTIVE"]:::ctx
    CP["MultiTenantConnectionProvider<br/>getConnection(1001) intercepts acquisition"]:::ctx
    POOL["HikariCP pool<br/>shared physical connections"]:::pool
  end

  subgraph PG["PostgreSQL instance :5432 - database tenantguard"]
    subgraph PUB["schema: public (global)"]
      T_TEN["tenants<br/>id, slug, schema_name, status, created_at"]:::pub
      T_USR["users<br/>id, tenant_id FK, email, password_hash, role, status"]:::pub
      T_MIG["flyway_schema_history<br/>platform migrations"]:::pub
    end
    subgraph SCH_A["schema: tenant_1001 (Tenant A)"]
      A_ORD["orders"]:::ta
      A_ITM["order_items"]:::ta
      A_IDM["idempotency_keys"]:::ta
    end
    subgraph SCH_B["schema: tenant_1002 (Tenant B)"]
      B_ORD["orders"]:::tb
      B_ITM["order_items"]:::tb
      B_IDM["idempotency_keys"]:::tb
    end
  end

  REQ --> TC
  TC --> REG
  REG -->|"schema_name"| CP
  CP -->|"acquire connection"| POOL
  POOL ==>|"SET search_path TO tenant_1001, public"| SCH_A
  SCH_A -.->|"fallback only for names absent from tenant schema"| PUB
  POOL --x|"NOT RESOLVABLE: tenant_1002 not on search_path"| SCH_B
  REG -.->|"SELECT via role order_svc (read only)"| T_TEN

  linkStyle 4 stroke:#2e7d32,stroke-width:3px
  linkStyle 6 stroke:#c62828,stroke-width:2px,stroke-dasharray:5 3
```

## 4b. Connection Acquisition and Release

```mermaid
sequenceDiagram
  autonumber
  participant S as OrderService (JPA / JDBC)
  participant P as MultiTenantConnectionProvider
  participant H as HikariCP
  participant D as PostgreSQL

  S->>P: getConnection(tenantIdentifier = 1001)
  P->>H: borrow physical connection
  H-->>P: connection (previous state unknown)
  P->>D: SET search_path TO tenant_1001, public
  P-->>S: connection bound to tenant 1001
  S->>D: SELECT / INSERT / UPDATE with unqualified table names
  D-->>S: results resolved only in tenant_1001, then public
  S->>P: releaseConnection(tenantIdentifier = 1001)
  P->>D: RESET search_path
  P->>H: return connection to pool
  Note over P,D: Reset on release prevents a pooled connection from leaking tenant 1001 into the next request
```

## 5. Deployment and Network Architecture

```mermaid
flowchart TB
  classDef ext fill:#08427b,stroke:#052e56,color:#ffffff
  classDef edge fill:#6b4fbb,stroke:#45308a,color:#ffffff
  classDef svc fill:#1168bd,stroke:#0b4884,color:#ffffff
  classDef db fill:#2e7d32,stroke:#1b5e20,color:#ffffff
  classDef obs fill:#ef6c00,stroke:#b53d00,color:#ffffff
  classDef sys fill:#eceff1,stroke:#546e7a,color:#263238
  classDef bad fill:#ffebee,stroke:#c62828,color:#7f0000
  classDef stretch fill:#f5f5f5,stroke:#757575,stroke-width:2px,stroke-dasharray:6 4,color:#424242

  CLIENT(["Client browser or curl<br/>https://tenantguard.local"]):::ext
  ROGUE(["Debug pod in another namespace<br/>or direct pod access attempt"]):::bad
  INET(["Internet egress"]):::bad

  subgraph HOST["Workstation (Docker Desktop / Linux host)"]
    subgraph K8S["Kubernetes cluster (kind or Minikube) with a NetworkPolicy-enforcing CNI (Calico or Cilium)"]

      subgraph NS_ING["Namespace: ingress-nginx"]
        ING["ingress-nginx-controller<br/>Deployment + Service NodePort 80 / 443<br/>Ingress rules: /api/auth, /api/tenants, /api/orders"]:::edge
      end

      subgraph NS_APP["Namespace: tenantguard-app - NP-1 default-deny ingress and egress"]
        AUTH["auth-service<br/>Deployment x2, Service ClusterIP :8081<br/>probes /actuator/health, non-root UID 10001"]:::svc
        TEN["tenant-service<br/>Deployment x2, Service ClusterIP :8082<br/>probes /actuator/health, non-root UID 10001"]:::svc
        ORD["order-service<br/>Deployment x2, Service ClusterIP :8083<br/>probes /actuator/health, non-root UID 10001"]:::svc
      end

      subgraph NS_DATA["Namespace: tenantguard-data - NP-1 default-deny ingress"]
        PG[("PostgreSQL StatefulSet x1<br/>Service ClusterIP :5432<br/>PersistentVolumeClaim")]:::db
      end

      subgraph NS_MON["Namespace: monitoring"]
        PROM["Prometheus"]:::obs
        GRAF["Grafana"]:::obs
      end

      subgraph NS_SYS["Namespace: kube-system"]
        DNS["CoreDNS :53"]:::sys
      end

      subgraph NS_FUT["Stretch namespaces (not in core milestone)"]
        ISTIO["istio-system<br/>Istio Gateway, STRICT mTLS"]:::stretch
        VAULT["vault<br/>HashiCorp Vault"]:::stretch
      end
    end
  end

  CLIENT -->|"HTTPS :443"| ING
  ING -->|"NP-2 ALLOW TCP 8081"| AUTH
  ING -->|"NP-2 ALLOW TCP 8082"| TEN
  ING -->|"NP-2 ALLOW TCP 8083"| ORD

  AUTH -->|"NP-3 ALLOW TCP 5432"| PG
  TEN -->|"NP-3 ALLOW TCP 5432"| PG
  ORD -->|"NP-3 ALLOW TCP 5432"| PG

  NS_APP -->|"NP-4 egress ALLOW UDP+TCP 53"| DNS
  PROM -->|"NP-5 ALLOW scrape TCP 9090"| NS_APP
  PROM --> GRAF

  ROGUE --x|"DENIED by NP-1 and NP-3"| PG
  CLIENT --x|"DENIED no route to pod network, no NodePort for services"| NS_APP
  ORD --x|"DENIED NP-1 lateral order to auth"| AUTH
  ORD --x|"DENIED NP-1 default egress"| INET

  ISTIO -.->|"alt. entry"| NS_APP
  VAULT -.->|"secrets"| NS_APP

  linkStyle 10,11,12,13 stroke:#c62828,stroke-width:2px,stroke-dasharray:5 3
```

## 6. CI/CD and Operations Flow

```mermaid
flowchart TB
  classDef step fill:#e3f2fd,stroke:#1565c0,color:#0d2f57
  classDef sec fill:#fff3e0,stroke:#e65100,color:#5a2300
  classDef gate fill:#fffde7,stroke:#f9a825,color:#4d3b00
  classDef fail fill:#ffebee,stroke:#c62828,color:#7f0000
  classDef run fill:#e8f5e9,stroke:#2e7d32,color:#1b4d1f

  DEV["Developer<br/>git commit and push feature branch"]:::step --> PR["Pull Request opened<br/>GitHub Actions trigger: pull_request"]:::step

  subgraph S1["Stage 1 - Verify (JDK 21, Maven cache)"]
    UT["Unit tests<br/>mvn test (JUnit 5)<br/>tenant resolution, authz, validation"]:::step --> IT["Integration tests<br/>mvn verify with Testcontainers PostgreSQL<br/>migrations, schema routing, cross-tenant negative tests"]:::step
  end
  G1{"Tests green?"}:::gate

  subgraph S2["Stage 2 - Application security (parallel jobs)"]
    SAST["SAST: Semgrep<br/>Java and OWASP rulesets"]:::sec
    SCA["SCA: Trivy fs<br/>dependency CVEs from pom.xml"]:::sec
  end
  G2{"Policy gate:<br/>no unwaived HIGH or CRITICAL"}:::gate

  subgraph S3["Stage 3 - Package"]
    BLD["Multi-stage Docker build<br/>builder: Maven + JDK 21<br/>runtime: slim JRE, USER 10001, no secrets in layers"]:::step --> IMG["Container image scan<br/>Trivy image, fail on HIGH and CRITICAL"]:::sec
  end
  G3{"Image clean?"}:::gate
  REGY["Image tagged with git SHA<br/>pushed to registry or kind load"]:::step

  subgraph S4["Stage 4 - Deploy (local Kubernetes, manual or self-hosted runner)"]
    APPLY["kubectl apply -k k8s/overlays/local<br/>Deployments, Services, Ingress, NetworkPolicies"]:::step --> ROLL["Rolling update<br/>readiness probe gates traffic<br/>kubectl rollout status"]:::step
  end
  G4{"Rollout healthy?"}:::gate
  UNDO["kubectl rollout undo<br/>previous ReplicaSet restored"]:::fail
  BLOCK["Merge blocked<br/>findings reported on the PR"]:::fail

  subgraph S5["Stage 5 - Runtime observability"]
    PROM["Prometheus scrapes :9090<br/>/actuator/prometheus"]:::run --> GRAF["Grafana dashboards<br/>latency p50/p95/p99, errors, DB pool"]:::run
  end

  PR --> UT
  IT --> G1
  G1 -->|"yes"| SAST
  G1 -->|"yes"| SCA
  G1 -->|"no"| BLOCK
  SAST --> G2
  SCA --> G2
  G2 -->|"pass"| BLD
  G2 -->|"fail"| BLOCK
  IMG --> G3
  G3 -->|"yes"| REGY
  G3 -->|"no"| BLOCK
  REGY --> APPLY
  ROLL --> G4
  G4 -->|"yes"| PROM
  G4 -->|"no"| UNDO
  GRAF -.->|"incidents and findings feed next PR"| DEV
  BLOCK -.->|"fix and push"| DEV
```


## 7. Entity-Relationship Diagram (ERD)

```mermaid
erDiagram
  tenants["public.tenants"] {
    bigint id PK "tenant id, used in schema name tenant_{id}"
    varchar slug UK "login hint, lowercase"
    varchar display_name
    varchar schema_name UK "validated against ^tenant_[0-9]+$"
    varchar status "ACTIVE, SUSPENDED, DEACTIVATED"
    varchar provisioning_status "PENDING, READY, FAILED"
    timestamptz created_at
    timestamptz deactivated_at "nullable"
  }

  users["public.users"] {
    uuid id PK
    uuid tenant_id "NULL only for PLATFORM_ADMIN; no cross-service FK"
    varchar email "unique per tenant, case-insensitive"
    varchar password_hash "bcrypt or argon2, never plaintext"
    varchar role "PLATFORM_ADMIN, TENANT_ADMIN, ORDER_MANAGER, ORDER_VIEWER"
    varchar status "PENDING_EMAIL, ACTIVE, LOCKED, DISABLED"
    int failed_login_count
    timestamptz last_login_at "nullable"
    timestamptz created_at
  }

  tenant_memberships["public.tenant_memberships"] {
    uuid id PK
    uuid tenant_id "logical ref to public.tenants; no cross-service FK"
    varchar normalized_email
    varchar role "tenant roles only; administrator-provisioned"
    varchar status "PENDING, ACTIVE"
    uuid user_id FK "nullable until registration"
    uuid created_by FK
    timestamptz created_at
    timestamptz activated_at
  }

  email_verification_codes["public.email_verification_codes"] {
    uuid id PK
    varchar normalized_email
    char code_hash UK "SHA-256 of random one-time code"
    timestamptz created_at
    timestamptz expires_at
    int failed_attempts
    timestamptz consumed_at
  }

  auth_rate_limits["public.auth_rate_limits"] {
    char bucket_hash PK "hash of scope and IP/email subject"
    timestamptz window_started_at
    int request_count
  }

  orders["tenant_{id}.orders"] {
    uuid id PK
    varchar order_number UK "human-readable, unique per tenant"
    varchar customer_name
    varchar customer_email
    varchar status "PENDING, CONFIRMED, FULFILLED, CANCELLED"
    numeric total_amount
    varchar currency "ISO 4217 code"
    uuid created_by_user_id "logical ref to public.users.id, not enforced"
    int version "optimistic locking"
    timestamptz created_at
    timestamptz updated_at
  }

  order_items["tenant_{id}.order_items"] {
    uuid id PK
    uuid order_id FK
    varchar sku
    varchar product_name
    int quantity
    numeric unit_price
    numeric line_total
  }

  idempotency_keys["tenant_{id}.idempotency_keys"] {
    varchar idempotency_key PK "client-supplied Idempotency-Key header"
    varchar request_hash "detects same key with different payload"
    uuid order_id FK "nullable until the order is committed"
    int response_status
    jsonb response_body
    timestamptz created_at
    timestamptz expires_at "cleanup job removes expired rows"
  }

  users ||--o{ tenant_memberships : "created memberships; logical/auth-owned link"
  users ||--o| tenant_memberships : "registered account"
  tenants ||..o{ orders : "owns schema containing"
  users ||..o{ orders : "created by (cross-schema, logical)"
  orders ||--|{ order_items : "contains"
  orders ||--o| idempotency_keys : "created via"
```

# Tenant Service

Tenant Service owns tenant lifecycle, memberships, subscription plans, plan versions, and tenant subscription entitlement snapshots. It does not own login credentials or call Auth Service's database for identity data.

## Stack and persistence

- Java 27, Spring Boot 4.1.2, Spring Security Resource Server, Spring Data JPA
- PostgreSQL with Flyway; Hibernate runs with `ddl-auto=validate`
- Dedicated `tenant_service` schema and `tenant_service_flyway_schema_history`
- PostgreSQL Testcontainers integration tests

Tenant metadata and membership authorization are owned by `tenant_service`. The migration retains the old `public.tenants` registry as `tenant_service.legacy_tenant_registry`; runtime services do not read or write it. Tenant V2 archives legacy membership and invitation rows with migration outcomes. Tenant V3 verifies source/archive row counts, deletes the Auth-side source rows, and records the completed transfer for Auth V10.

| Tenant version | Purpose |
| --- | --- |
| V1 | Create the Tenant schema and core tenant, membership, subscription, and entitlement tables. |
| V2 | Archive and map legacy tenant, membership, and invitation data. |
| V3 | Verify the membership/invitation transfer, remove copied Auth-side rows, and signal completion to Auth. |

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/tenantguard` | PostgreSQL JDBC URL |
| `TENANT_DB_USERNAME` | `tenant_service` | Tenant Service database role |
| `TENANT_DB_PASSWORD` | empty | Tenant Service role password |
| `TENANT_MIGRATION_USERNAME` | `tenant_service_migrator` | Separate Flyway migration role |
| `TENANT_MIGRATION_PASSWORD` | empty | Migration role password; set separately from the runtime role |
| `AUTH_JWT_ISSUER` | `http://localhost:8081` | Required Auth JWT issuer |
| `AUTH_JWT_AUDIENCE` | `tenantguard-api` | Required JWT audience |
| `AUTH_JWT_JWK_SET_URI` | `http://localhost:8081/.well-known/jwks.json` | Auth public-key endpoint |
| `SERVER_PORT` | `8082` | HTTP port |
| `MAIL_HOST` / `MAIL_PORT` | `mailpit` / `1025` in `.env.example` | SMTP server used for invitations |
| `TENANT_INVITATION_ACCEPT_URL` | `http://localhost:3000/invitations/accept` | Frontend URL included in invitation emails |

Auth tokens use RS256. Tenant Service validates the signature through Auth's JWKS endpoint, issuer, audience, timestamps, and UUID subject. Auth sets `sub` and `global_user_id` to the same global identity UUID. Tenant roles always come from Tenant Service memberships, never from JWT role claims.

The sample paid plan versions have no configured commercial price. They remain `PENDING_PAYMENT`; no payment is implied. Seed entitlements and quotas are development examples and must be approved/configured before production.

## Local startup

1. Copy `.env.example` to `.env`, configure the Auth RSA keys and database passwords, and set separate `TENANT_DB_PASSWORD` and `TENANT_MIGRATION_PASSWORD` values.
2. Start the stack without resetting volumes:

   ```sh
   docker compose --profile local-mail up --build
   ```

   Invitation messages can be inspected at [http://localhost:8025](http://localhost:8025).

3. Check health and list plans using a valid Auth access token:

   ```sh
   curl http://localhost:8082/actuator/health
   curl -H "Authorization: Bearer $TOKEN" http://localhost:8082/api/v1/subscription-plans/plans
   ```

4. Create a tenant:

   ```sh
   curl -i -X POST http://localhost:8082/api/v1/tenants \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"name":"Acme Corporation","slug":"acme-corporation","subscriptionPlanCode":"FREE"}'
   ```

`tenant-schema-bootstrap` provisions the runtime and migration roles. Flyway runs as `tenant_service_migrator`; the application runs as `tenant_service`, which has no public-schema access to legacy Auth records. Existing PostgreSQL volumes are preserved. If the bootstrap container has already completed with an older configuration, recreate it and the dependent services with `docker compose up -d --force-recreate tenant-schema-bootstrap auth-service tenant-service`.

For upgrades with legacy Auth membership or invitation data, follow the staged rollout in the Auth Service README. Apply Auth V9 first, then Tenant V2/V3, then Auth V10; do not deploy Auth V10 ahead of the Tenant transfer.

## API

All `/api/v1` endpoints require a valid Auth JWT.

| Method | Path | Authorization | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/v1/subscription-plans/plans` | Authenticated | Active plans and current published version entitlements |
| `POST` | `/api/v1/tenants` | Authenticated | Create tenant, OWNER membership, subscription, and entitlement snapshot |
| `GET` | `/api/v1/tenants` | Authenticated | List tenants with the caller's active membership |
| `GET` | `/api/v1/tenants/{tenantId}` | Active tenant member | Read tenant and subscription summary |
| `PATCH` | `/api/v1/tenants/{tenantId}` | OWNER | Update tenant name |
| `GET` | `/api/v1/memberships` | Authenticated | List the caller's active memberships |
| `GET` | `/api/v1/tenants/{tenantId}/memberships` | OWNER or ADMIN | List tenant memberships |
| `POST` | `/api/v1/tenants/{tenantId}/invitations` | OWNER or ADMIN | Email a one-time invitation; ADMIN can invite MEMBER only |
| `GET` | `/api/v1/tenants/{tenantId}/invitations` | OWNER or ADMIN | List tenant invitations |
| `DELETE` | `/api/v1/tenants/{tenantId}/invitations/{invitationId}` | OWNER or ADMIN | Revoke a pending invitation |
| `POST` | `/api/v1/invitations/accept` | Authenticated invited user | Accept an invitation whose email matches the JWT |

Tenant creation accepts only `name`, `slug`, and `subscriptionPlanCode`. Ownership is derived from the verified `global_user_id` claim. Free versions with zero price become active; other selected plans remain pending payment.

Create an invitation with `{"email":"member@example.com","role":"MEMBER"}`. The API returns invitation metadata but never returns the secret token; the recipient receives it by email. Accept with `{"token":"<token-from-email>"}` while authenticated as that same email address. Invitations expire after seven days.

## Migrations and tests

Migrations are in `src/main/resources/db/migration`. Do not edit an applied migration; add a new version instead.

```sh
./mvnw --batch-mode clean verify
```

On Windows, run `.\mvnw.cmd --batch-mode clean verify`. Integration tests require Docker and use PostgreSQL Testcontainers; H2 is not used.

## Limitations

- Payment collection/confirmation, renewals, membership role updates/removal, and order operations are not part of Phase 1. Pending legacy memberships without a reusable invitation require an administrator to send a new invitation.
- Seed paid prices are intentionally unset and development quotas are illustrative.

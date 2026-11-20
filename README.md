# TenantGuard OMS

TenantGuard OMS is a multi-tenant order management system under development. Auth Service and Tenant Service are implemented; Order Service, tenant-specific order schemas, and cross-service provisioning remain planned. See the [architecture consistency report](docs/architecture/ARCHITECTURE-CONSISTENCY-REPORT.md) for verified implementation status.

## Project Overview

TenantGuard brings together the following core concerns:

- Global user identity independent of tenant membership; tenant roles are managed separately
- Public registration and email verification
- Secure password handling and credential validation
- JWT-based access token issuance and validation
- Tenant registry, memberships, invitations, and subscription management
- Planned: order management scoped to a tenant's data
- PostgreSQL-backed Auth and Tenant schemas; per-tenant Order schemas are planned, not operational

The system is intended to support a clear separation of responsibility between services while keeping the platform easy to run locally with Docker and Kubernetes-oriented deployment patterns.

## Architecture

The repository currently includes these service domains:

- `services/auth-service` — authentication, login flows, user identity, email verification, JWT issuance, external identity handling
- `services/tenant-service` — tenant lifecycle and registry management
- `services/order-service` — planned; no Order Service implementation is present yet

At a high level, the platform uses:

- Java 27 and Spring Boot for service implementation
- PostgreSQL as the database layer
- Docker Compose for local development and service orchestration
- Kubernetes-oriented deployment structure for production-style container orchestration
- NGINX ingress and service-to-service routing patterns in the architecture docs

## Security and Design Principles

TenantGuard is designed around the following boundaries:

- Auth issues a global-identity JWT; it does not carry authoritative tenant IDs or tenant roles
- Tenant Service checks current membership and role for its implemented tenant APIs
- Planned Order operations must validate tenant membership before tenant-schema access
- User credentials are hashed with BCrypt before persistence
- Email verification is used for public registration flows
- OAuth/OIDC participants are handled as external identity mappings rather than direct password-style authentication
- Tenant metadata currently lives in Tenant Service tables; tenant-specific Order schemas are a target architecture, not a current feature

## Repository Structure

```text
TenantGuard/
├── .github/                     # CI workflows and GitHub automation
├── docs/                       # Architecture documentation and diagrams
├── infrastructure/             # Infrastructure and database bootstrap assets
├── k8s/                        # Kubernetes deployment manifests
├── observability/              # Logging, metrics, tracing support
├── scripts/                    # Utility scripts
├── services/
│   ├── auth-service/           # Authentication and identity service
│   ├── order-service/          # Order management service
│   └── tenant-service/         # Tenant lifecycle service
├── docker-compose.yml          # Local service orchestration
├── .env.example                # Example environment configuration
├── .gitignore                  # Repository ignore rules
├── README.md                   # Project overview
└── ...
```

## Local Development

### Prerequisites

- Java 27
- Docker Desktop or Docker Engine
- Docker Compose
- Maven or the repo's Maven wrapper for service-level builds

### Run locally

1. Copy the example environment file and update values for your machine:

```bash
cp .env.example .env
```

2. Start the shared local stack:

```bash
docker compose up --build
```

3. Access the service endpoints as defined by your local configuration and compose setup.

> Never commit real credentials, JWT private keys, or database secrets to the repository. Store them in a local environment file or your secure deployment secret store.

## Auth Service Notes

The `auth-service` is one of the central components of the platform and manages:

- registration and verification flows
- login and credential verification
- global user identities and JWT issuance for downstream identity authentication
- no tenant membership or tenant-scoped authorization; those responsibilities belong to Tenant Service

The service also includes integration tests using PostgreSQL Testcontainers, which helps ensure the application can validate real database behavior in a Dockerized environment.

## Documentation

Additional project details are available in the `docs/` folder, including architecture diagrams and deeper system design documentation. These references provide a clearer picture of how the multi-tenant model, service boundaries, and tenant isolation are intended to work.

## Current Status

This repository is a work-in-progress multi-service platform focused on secure, tenant-aware business operations. The project is structured for modular growth while preserving clear separation between shared platform concerns and tenant-specific behavior.

## License

This project does not currently specify a license in the repository root. If you plan to distribute or publish this codebase, add an explicit license file and review the legal requirements before release.

# TenantGuard OMS

TenantGuard OMS is a multi-tenant order management system designed for secure, isolated tenant operations. The platform is built around a modular Java/Spring Boot architecture, with separate services for identity and authentication, tenant lifecycle management, and order processing.

## Project Overview

TenantGuard brings together the following core concerns:

- Multi-tenant user identity and role management
- Public registration and email verification
- Secure password handling and credential validation
- JWT-based access token issuance and validation
- Tenant registry and schema provisioning for isolated data boundaries
- Order management scoped to a tenant's data
- PostgreSQL-backed persistence with a public registry and per-tenant schemas

The system is intended to support a clear separation of responsibility between services while keeping the platform easy to run locally with Docker and Kubernetes-oriented deployment patterns.

## Architecture

The repository currently includes the following service domains:

- `services/auth-service` — authentication, login flows, user identity, email verification, JWT issuance, external identity handling
- `services/tenant-service` — tenant lifecycle and registry management
- `services/order-service` — order operations, tenant-scoped business logic, data isolation

At a high level, the platform uses:

- Java 27 and Spring Boot for service implementation
- PostgreSQL as the database layer
- Docker Compose for local development and service orchestration
- Kubernetes-oriented deployment structure for production-style container orchestration
- NGINX ingress and service-to-service routing patterns in the architecture docs

## Security and Design Principles

TenantGuard follows a defensive software design model:

- Tenant-specific access is enforced through scoped JWT claims and tenant checks
- Role-based access controls are applied at the application layer
- User credentials are hashed with BCrypt before persistence
- Email verification is used for public registration flows
- OAuth/OIDC participants are handled as external identity mappings rather than direct password-style authentication
- Tenant data is retained in isolated schemas, with a shared public registry for platform metadata

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
- account membership state
- tenant-scoped authorization assumptions
- JWT issuance for downstream services

The service also includes integration tests using PostgreSQL Testcontainers, which helps ensure the application can validate real database behavior in a Dockerized environment.

## Documentation

Additional project details are available in the `docs/` folder, including architecture diagrams and deeper system design documentation. These references provide a clearer picture of how the multi-tenant model, service boundaries, and tenant isolation are intended to work.

## Current Status

This repository is a work-in-progress multi-service platform focused on secure, tenant-aware business operations. The project is structured for modular growth while preserving clear separation between shared platform concerns and tenant-specific behavior.

## License

This project does not currently specify a license in the repository root. If you plan to distribute or publish this codebase, add an explicit license file and review the legal requirements before release.

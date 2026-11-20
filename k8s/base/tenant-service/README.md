# Tenant Service Kubernetes Base

Apply the base with:

```sh
kubectl apply -k k8s/base/tenant-service
```

Before applying, make sure the cluster has:

- A PostgreSQL Service reachable at the `DATABASE_URL` in `configmap.yaml`. No PostgreSQL StatefulSet is included in this base.
- The Tenant Service runtime and migration database roles and bootstrap permissions. Use separate least-privilege credentials.
- Auth Service reachable as `auth-service:8081`; `AUTH_JWT_ISSUER` and `AUTH_JWT_AUDIENCE` must exactly match Auth's signing configuration.
- An NGINX Ingress controller and a TLS Secret named `tenantguard-tls` for `tenantguard.local`.
- The image `ghcr.io/nguyenledaiphi/tenant-service`. Make the GHCR package public or configure an image-pull Secret for private package access.
- Secret `tenant-service-db-credentials` with keys `TENANT_DB_USERNAME`, `TENANT_DB_PASSWORD`, `TENANT_MIGRATION_USERNAME`, and `TENANT_MIGRATION_PASSWORD`.
- Optional secret `tenant-service-mail-credentials` with keys `MAIL_USERNAME` and `MAIL_PASSWORD`. Configure the SMTP settings in the ConfigMap for the chosen mail provider.

Create Secrets through the cluster's approved secret-management process; do not commit credential values. The workload runs as non-root with a read-only root filesystem. Kubernetes health probes and service traffic use port 8082; actuator endpoints are not exposed through the Ingress.

The GitHub workflow verifies the service and publishes `latest` plus an immutable `sha-<commit>` image to GHCR after a successful push to `main`. For repeatable deployments, replace `latest` with the desired SHA tag in a Kustomize overlay. The workflow does not deploy to a cluster; deployment credentials and an environment-specific release process are not configured in this repository.

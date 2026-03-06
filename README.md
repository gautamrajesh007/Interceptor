# Interceptor

<div align="center">

**A PostgreSQL Peer Authorization Proxy**

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

_Intercept, authorize, and audit PostgreSQL queries in real-time with peer approval workflows._

</div>

---

## Overview

Interceptor is a database authorization proxy that sits between your applications and PostgreSQL. It intercepts SQL traffic, applies policy, and requires human approval for sensitive operations. The web dashboard provides real-time visibility and action controls.

## Feature Modules

The project is documented as independent feature modules so changes can be added/removed with low friction:

| Module | Purpose | Main APIs | Can be disabled/changed by |
| --- | --- | --- | --- |
| Auth | JWT session management | `/api/login`, `/api/logout` | Replacing auth provider/JWT policy |
| Query Workflow | Blocking, approvals, peer votes | `/api/blocked*`, `/api/approve`, `/api/reject`, `/api/vote` | Keyword policy + approval settings |
| Users | Admin user lifecycle | `/api/users*` | Role policy and identity model |
| Config | Runtime config snapshot/update request | `/api/config` | Config backend implementation |
| Metrics | Live counters | `/api/metrics` + `/topic/metrics` | Metrics collection source |
| Audit | Security/activity trace | `/api/audit*`, `/topic/logs` | Retention policy + storage |
| Realtime | STOMP subscriptions | `/ws`, `/topic/*` | Broker/topic evolution |

This layout keeps docs and implementation decoupled: each module can evolve independently with explicit compatibility notes.

---

## Architecture

```text
Client App -> Interceptor Proxy (:5432) -> Target PostgreSQL (:5433)
                     |
                     +-> Dashboard/API (HTTPS, default :443)
                     +-> Redis pub/sub (TLS :6380)
```

---

## Quick Start

### Prerequisites

- Java 21+
- Docker + Docker Compose
- Maven (or `./mvnw`)

### Installation

1. Clone repository

```bash
git clone https://github.com/gautamrajesh007/Interceptor.git
cd Interceptor
```

2. Start infrastructure

```bash
docker-compose up -d
```

3. Generate local certs

```bash
chmod +x scripts/cert_gen.sh
./scripts/cert_gen.sh
```

4. Run app (dev profile)

```bash
source src/main/resources/creds.env
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

5. Open dashboard

```text
https://localhost
Default credentials: admin / 14495abc
```

### Docker Services (default)

- Target PostgreSQL: `localhost:5433`
- Interceptor metadata PostgreSQL: `localhost:5434`
- Redis TLS: `localhost:6380`

---

## Configuration

Core settings are in `src/main/resources/application-dev.yaml`.

- Proxy: `proxy.listen-port`, `proxy.target-*`, `proxy.block-by-default`
- Classification: `proxy.critical-keywords`, `proxy.allowed-keywords`
- Approval: `approval.peer-enabled`, `approval.min-votes`
- Security: `jwt.*`, `server.ssl.*`, `proxy.ssl.*`

---

## API Usage

### Query Workflow Summary

- `ADMIN`: direct approve/reject.
- `PEER`: casts votes (`APPROVE` or `REJECT`) when peer flow is enabled.
- Replay protection fields (`nonce`, `timestamp`) are supported on approve/reject/vote requests.

### REST Examples

#### Login

```bash
curl -X POST https://localhost/api/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"14495abc"}'
```

#### Get Pending Queries

```bash
curl https://localhost/api/blocked \
  -H "Authorization: Bearer <token>"
```

#### Approve Query

```bash
curl -X POST https://localhost/api/approve \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"id":1,"nonce":"unique-nonce","timestamp":"1738855200000"}'
```

#### Vote on Query (Peer Mode)

```bash
curl -X POST https://localhost/api/vote \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"id":1,"vote":"APPROVE","nonce":"unique-nonce","timestamp":"1738855200000"}'
```

### Compatibility Notes

The frontend currently supports both canonical and legacy contracts to reduce breakage during migrations:

- Canonical pending endpoints: `/api/blocked`, `/api/blocked/all`
- Legacy fallback endpoints still tolerated by UI: `/api/pending`, `/api/pending/all`
- Canonical realtime topic: `/topic/blocked`; UI also listens to legacy `/topic/queries`
- Login canonical response: `{ "token": "..." }` (UI can derive user identity from JWT claims)

For full endpoint and payload details, see `API_GUIDE.md`.

---

## Security Notes

- JWT-based stateless auth
- BCrypt password hashing
- TLS-enabled web/proxy/redis paths in dev profile
- Nonce + timestamp replay checks on sensitive mutation endpoints
- Audit log coverage for authentication and query decision flow

---

## Development

### Run Tests

```bash
./mvnw test
```

### Build

```bash
./mvnw clean package -DskipTests
java -jar target/interceptor-0.0.1-SNAPSHOT.jar
```

---

## Project Structure

```text
src/main/java/com/proxy/interceptor/
  config/       security, websocket, redis wiring
  controller/   REST controllers
  dto/          request/response contracts
  model/        JPA entities
  proxy/        Netty proxy pipeline
  repository/   data access
  security/     JWT auth/filter components
  service/      business logic
```

---

## Extending or Removing Features

To keep changes modular, update docs and code using this pattern per feature:

1. Add/remove one module row in `Feature Modules`.
2. Add/remove one section in `API_GUIDE.md`.
3. Add/remove route/topic entries in frontend adapters (`src/main/resources/static/js/api.js`, `src/main/resources/static/js/dashboard.js`).
4. Note compatibility behavior (supported aliases, deprecation window, removal date).

This avoids cross-cutting doc edits and keeps reversions straightforward.

---

## Contributing

Contributions are welcome. Open an issue for major changes before submitting a PR.

## License

MIT - see `LICENSE`.

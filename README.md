# Interceptor

<div align="center">

**A PostgreSQL Peer Authorization Proxy with Dynamic Risk Scoring**

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

_Intercept, authorize, and audit PostgreSQL queries in real-time with peer approval workflows and dynamic risk scoring._

</div>

---

## Overview

Interceptor is a database authorization proxy that sits between your applications and PostgreSQL. It intercepts SQL traffic, applies policy, and requires human approval for sensitive operations. The web dashboard provides real-time visibility and action controls.

### Key Features

- **PostgreSQL Wire Protocol Proxy**: Netty-based proxy that intercepts both Simple Query and Extended Query protocols
- **Dynamic Risk Scoring (DRS)**: Multi-factor risk assessment that dynamically determines required approval counts
- **Peer Approval Workflow**: Configurable peer voting system for query approvals
- **Real-time Dashboard**: WebSocket-powered dashboard for live query monitoring and approval
- **Comprehensive Audit Trail**: Full audit logging of all authentication and query decision events
- **TLS Everywhere**: End-to-end encryption for proxy, dashboard, and Redis connections

---

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
| DRS | Dynamic risk scoring | Internal service | Risk scoring algorithm and weights |

This layout keeps docs and implementation decoupled: each module can evolve independently with explicit compatibility notes.

---

## Architecture

```text
Client App -> Interceptor Proxy (:5432) -> Target PostgreSQL (:5433)
                     |
                     +-> Dashboard/API (HTTPS, default :443)
                     +-> Redis pub/sub (TLS :6380)
```

### Core Components

**Proxy Pipeline**
- `ProxyServer`: Netty-based PostgreSQL proxy server
- `ClientHandler`: Handles client connections and query interception
- `WireProtocolHandler`: Parses PostgreSQL wire protocol messages
- `SqlClassifier`: Classifies queries based on keywords
- `ast/JSqlParserAnalyzer`: SQL AST analysis for operation type extraction

**Dynamic Risk Scoring**
- `DynamicRiskService`: Orchestrates four sub-score calculators
- `SyntaxScoreCalculator`: Analyzes SQL complexity (depth, joins)
- `DataSensitivityCalculator`: Scores based on table/column sensitivity
- `BehaviorScoreCalculator`: Tracks query patterns per connection
- `ContextScoreCalculator`: Scores based on IP and time-of-day

Risk formula: `R = min(1.0, w1*S_syn + w2*S_data + w3*S_beh + w4*S_ctx)`
Required approvals: `T(R) = ceil(T_min + (T_max - T_min) * R^gamma)`

**Security**
- `JwtTokenProvider`: JWT token generation and validation
- `JwtAuthFilter`: JWT authentication for REST endpoints
- `WebSocketAuthInterceptor`: JWT authentication for WebSocket connections

**Messaging**
- `QueryEventPublisher`: Publishes events to Redis for real-time updates
- `RedisMessageHandler`: Handles Redis pub/sub messages

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

### Proxy Settings

- `proxy.listen-port`: Port where proxy listens (default: 5432)
- `proxy.target-host`: PostgreSQL target host (default: localhost)
- `proxy.target-port`: PostgreSQL target port (default: 5433)
- `proxy.block-by-default`: Whether to block queries by default (default: true)
- `proxy.critical-keywords`: SQL keywords that require approval
- `proxy.allowed-keywords`: SQL keywords that bypass blocking

### Dynamic Risk Scoring Settings

- `risk-scoring.enabled`: Enable/disable DRS (default: true)
- `risk-scoring.syntax-weight`: Weight for syntax complexity (default: 0.2)
- `risk-scoring.data-weight`: Weight for data sensitivity (default: 0.4)
- `risk-scoring.behavior-weight`: Weight for behavioral patterns (default: 0.3)
- `risk-scoring.context-weight`: Weight for context factors (default: 0.1)
- `risk-scoring.min-approvals`: Minimum required approvals (default: 1)
- `risk-scoring.max-approvals`: Maximum required approvals (default: 5)
- `risk-scoring.gamma`: Gamma parameter for threshold function (default: 2.0)
- `risk-scoring.sensitivity-map`: Table/column sensitivity scores
- `risk-scoring.business-hour-start`: Start of business hours (default: 9)
- `risk-scoring.business-hour-end`: End of business hours (default: 18)
- `risk-scoring.trusted-ip-prefixes`: Trusted IP prefixes (default: 10., 172.16., 192.168., 127.)

### Security Settings

- `jwt.secret`: JWT signing secret
- `jwt.expiration`: JWT token expiration in milliseconds (default: 86400000)
- `server.ssl.*`: HTTPS/TLS configuration
- `proxy.ssl.*`: Proxy TLS configuration

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

### Run Single Test

```bash
./mvnw test -Dtest=ClassName#methodName
```

### Build

```bash
./mvnw clean package -DskipTests
java -jar target/interceptor-0.0.1-SNAPSHOT.jar
```

### Database Setup

```bash
# Setup test database with sample data
PGPASSWORD='testpass@123' psql -h localhost -p 5432 -U testuser -d testdb "sslmode=require" < scripts/setup_test_db.sh

# Create test PEER users via API
./scripts/create_test_peers.sh
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
  service/risk/ Dynamic Risk Scoring calculators
  messaging/    Redis pub/sub
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

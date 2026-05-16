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
- **Dynamic Risk Scoring (DRS)**: Four-factor risk assessment that dynamically determines required approval counts
- **Peer Approval Workflow**: Configurable peer voting system with dynamic thresholds capped to registered peer count
- **Real-time Dashboard**: WebSocket-powered dashboard for live query monitoring and approval
- **Comprehensive Audit Trail**: Full audit logging of all authentication, proxy events, and query decisions with configurable retention
- **TLS Everywhere**: End-to-end TLSv1.3 encryption for proxy, dashboard, PostgreSQL, and Redis connections
- **Unified API Envelope**: All REST responses wrapped in a consistent `ApiResponse<T>` format

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
                     +-> Dashboard/API (HTTPS :443)
                     +-> Redis pub/sub (TLS :6380)
                     +-> Metadata PostgreSQL (:5434)
```

### Core Components

**Proxy Pipeline**
- `ProxyServer`: Netty-based PostgreSQL proxy server with native transport (kqueue/epoll)
- `ClientHandler`: Handles client connections, SSLRequest negotiation, and query interception
- `ServerHandler`: Handles backend PostgreSQL responses
- `BackendSslNegotiationHandler`: Negotiates TLS with the backend PostgreSQL server
- `WireProtocolHandler`: Parses PostgreSQL wire protocol messages (Simple Query `Q`, Parse `P`, Bind `B`, Describe `D`, Execute `E`, Sync `S`)
- `SqlClassifier`: Classifies queries via AST analysis with naive-string-match fallback
- `ast/JSqlParserAnalyzer`: SQL AST analysis for operation type extraction (via JSqlParser 5.3)
- `ConnectionState`: Per-connection state tracking (client IP, SSL negotiation, extended batch buffering)
- `ProxyContext`: Shared dependency context record passed to all channel handlers
- `EventLoopGroupFactory`: Native transport selection (kqueue on macOS, epoll on Linux)

**Dynamic Risk Scoring**
- `DynamicRiskService`: Orchestrates four sub-score calculators
- `SyntaxScoreCalculator`: Analyzes SQL complexity (subquery depth, join count)
- `DataSensitivityCalculator`: Scores based on table/column sensitivity mappings
- `BehaviorScoreCalculator`: Tracks query patterns per connection
- `ContextScoreCalculator`: Scores based on IP trustworthiness and time-of-day

Risk formula: `R = min(1.0, w1*S_syn + w2*S_data + w3*S_beh + w4*S_ctx)`
Required approvals: `T(R) = ceil(T_min + (T_max - T_min) * R^gamma)`
Dynamic cap: `T_max = min(config.maxApprovals, registeredPeerCount)`

**Security**
- `JwtTokenProvider`: JWT token generation and validation (HMAC, jjwt 0.13.0)
- `JwtAuthFilter`: JWT authentication filter for REST endpoints with token version validation
- `WebSocketAuthInterceptor`: JWT authentication for STOMP WebSocket connections
- `SecurityConfig`: URL-based access rules + `@EnableMethodSecurity` for `@PreAuthorize` annotations
- `ReplayProtectionService`: Nonce + timestamp replay checks backed by Redis TTL keys

**API Layer**
- `GlobalExceptionHandler`: Centralized `@RestControllerAdvice` for `EntityNotFoundException` and `IllegalArgumentException`
- `ApiResponse<T>`: Unified response envelope (`{ "success": bool, "data": T, "error": string }`)

**Messaging**
- `QueryEventPublisher`: Publishes events to Redis for real-time updates
- `RedisMessageHandler`: Handles Redis pub/sub messages
- `WebSocketNotificationService`: Broadcasts to STOMP topics (`/topic/blocked`, `/topic/approvals`, `/topic/votes`, `/topic/logs`, `/topic/metrics`)

**Utilities**
- `RequestUtils`: Client IP extraction (X-Forwarded-For aware) and authenticated username retrieval
- `NetworkUtils`: System IP detection and subnet comparison

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
- `proxy.critical-keywords`: SQL keywords that require approval (default: DROP,ALTER,TRUNCATE,DELETE,GRANT,REVOKE,UPDATE,INSERT)
- `proxy.allowed-keywords`: SQL keywords that bypass blocking (default: SELECT,CREATE)
- `proxy.ssl.enabled`: Enable TLS for proxy connections

### Peer Approval Settings

- `approval.peer-enabled`: Enable peer voting workflow (default: true)
- `approval.min-votes`: Minimum votes for static threshold fallback (default: 2)

### Dynamic Risk Scoring Settings

- `risk-scoring.enabled`: Enable/disable DRS (default: true)
- `risk-scoring.syntax-weight`: Weight for syntax complexity (default: 0.2)
- `risk-scoring.data-weight`: Weight for data sensitivity (default: 0.4)
- `risk-scoring.behavior-weight`: Weight for behavioral patterns (default: 0.3)
- `risk-scoring.context-weight`: Weight for context factors (default: 0.1)
- `risk-scoring.min-approvals`: Minimum required approvals (default: 1)
- `risk-scoring.max-approvals`: Maximum required approvals (default: 20)
- `risk-scoring.gamma`: Gamma parameter for threshold function (default: 2.0)
- `risk-scoring.depth-coefficient`: Penalty coefficient for subquery depth (default: 0.15)
- `risk-scoring.join-coefficient`: Penalty coefficient for join count (default: 0.2)
- `risk-scoring.sensitivity-map`: Table/column sensitivity scores (map)
- `risk-scoring.business-hour-start`: Start of business hours (default: 9)
- `risk-scoring.business-hour-end`: End of business hours (default: 18)
- `risk-scoring.trusted-ip-prefixes`: Trusted IP prefixes (default: 10., 172.16., 192.168., 127.)

### Audit Settings

- `audit.retention-days`: Number of days to retain audit logs (default: 90)
- Cleanup runs daily at 2:00 AM via `@Scheduled` cron

### Security Settings

- `jwt.secret`: JWT HMAC signing secret
- `jwt.expiration`: JWT token expiration in milliseconds (default: 86400000 / 24h)
- `server.ssl.*`: HTTPS/TLS configuration for the dashboard (TLSv1.3, PKCS12)
- `proxy.ssl.*`: Proxy TLS configuration for PostgreSQL wire protocol connections

---

## API Response Envelope

All REST endpoints return responses wrapped in a unified `ApiResponse<T>` envelope:

```json
{
  "success": true,
  "data": { ... },
  "error": null
}
```

On error:

```json
{
  "success": false,
  "data": null,
  "error": "Error message"
}
```

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

Response:
```json
{
  "success": true,
  "data": { "token": "eyJhbGciOiJIUzI1NiJ9..." },
  "error": null
}
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
- Login canonical response: `{ "success": true, "data": { "token": "..." } }` (UI can derive user identity from JWT claims)

For full endpoint and payload details, see `API_GUIDE.md`.

---

## Domain Enums

| Enum | Values | Description |
| --- | --- | --- |
| `Role` | `ADMIN`, `PEER` | User roles |
| `Status` | `PENDING`, `APPROVED`, `REJECTED`, `EXPIRED` | Query lifecycle states |
| `QueryType` | `SIMPLE`, `EXTENDED` | PostgreSQL wire protocol type |
| `Vote` | `APPROVE`, `REJECT` | Peer vote values |
| `AuthMethod` | `PASSWORD`, `OAUTH`, `CERTIFICATE`, `HYBRID` | Authentication methods (future) |

---

## Security Notes

- JWT-based stateless auth with token version invalidation on logout
- BCrypt password hashing
- TLSv1.3-enabled web/proxy/redis/PostgreSQL paths in dev profile
- Nonce + timestamp replay checks (5-minute window) on sensitive mutation endpoints, backed by Redis
- Audit log coverage for authentication, proxy connection lifecycle, and query decision flow
- Method-level security via `@PreAuthorize` annotations on controllers
- Centralized error handling via `GlobalExceptionHandler`
- Self-deletion protection (admins cannot delete their own account)

---

## OpenAPI / Swagger

When running in dev profile, API documentation is available at:

- Swagger UI: `https://localhost/swagger-ui.html`
- OpenAPI JSON: `https://localhost/v3/api-docs`

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
  InterceptorApplication.java   main entry point, admin bootstrap
  config/       security, websocket, redis, proxy/approval/risk-scoring properties
  controller/   REST controllers + GlobalExceptionHandler
  dto/          request/response contracts (records)
  model/        JPA entities + enums (Role, Status, QueryType, Vote, AuthMethod)
  proxy/        Netty proxy pipeline + connection state
  proxy/ast/    SQL AST analysis (JSqlParser)
  repository/   Spring Data JPA repositories
  security/     JWT auth filter, token provider, WebSocket auth interceptor
  service/      business logic (auth, audit, blocked queries, metrics, replay protection, user, websocket)
  service/risk/ Dynamic Risk Scoring calculators
  messaging/    Redis pub/sub publishers and handlers
  util/         network and request utilities
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

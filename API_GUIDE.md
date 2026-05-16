# Interceptor Backend API Guide

This guide is the contract reference for frontend and integration clients.

## How to Use This Guide

The API is documented by feature module. To add/remove features safely:

1. Add/remove one module section.
2. Update only that section's endpoint table and payload examples.
3. Update compatibility notes for aliases/deprecations.

---

## Response Envelope

All REST responses are wrapped in a unified `ApiResponse<T>` envelope:

### Success

```json
{
  "success": true,
  "data": { ... },
  "error": null
}
```

### Error

```json
{
  "success": false,
  "data": null,
  "error": "Error message"
}
```

The `data` field contains the module-specific payload documented in each section below.

---

## Module 1: Authentication

JWT-based stateless authentication with token version invalidation.

### Endpoints

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| `POST` | `/api/login` | Public | Returns JWT token |
| `POST` | `/api/logout` | Public in filter, token-validated in handler | Invalidates current token version |

### Login Request

```json
{
  "username": "admin",
  "password": "14495abc"
}

```

Fields: `username` (`@NotBlank`), `password` (`@NotBlank`).

### Login Response (Current)

```json
{
  "success": true,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9..."
  },
  "error": null
}
```

### Login Error Response

```json
{
  "success": false,
  "data": null,
  "error": "Invalid credentials"
}
```

HTTP 401 on invalid credentials.

### Logout Request

Requires `Authorization: Bearer <token>` header.

### Logout Response

```json
{
  "success": true,
  "data": null,
  "error": null
}
```

Returns HTTP 400 with error message if the token is missing, invalid, expired, or already invalidated.

### Token Claims Used by Frontend

- `sub` -> username
- `role` -> role (`ADMIN` or `PEER`)
- `token_version` -> session invalidation support

### Token Version Invalidation

Logout increments the user's `tokenVersion` in the database. Subsequent requests with tokens carrying the old version are rejected at the `JwtAuthFilter` level (no SecurityContext is set).

### Compatibility Notes

- Some clients may still expect a `user` object from login. The current backend returns token-only inside the `data` field.
- Frontend derives user identity from JWT claims when `user` is absent.

---

## Module 2: Query Workflow

Core approval path for intercepted SQL.

### Roles

- `ADMIN`: approve/reject directly.
- `PEER`: can vote when peer approval is enabled.

### Endpoints

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| `GET` | `/api/blocked` | `ADMIN` or `PEER` | Pending queries |
| `GET` | `/api/blocked/all` | `ADMIN` or `PEER` | Full query history (last 100) |
| `GET` | `/api/blocked/{id}/votes` | `ADMIN` or `PEER` | Vote status details |
| `POST` | `/api/approve` | `ADMIN` | Master approve (direct) |
| `POST` | `/api/reject` | `ADMIN` | Master reject (direct) |
| `POST` | `/api/vote` | `ADMIN` or `PEER` | Vote path (`APPROVE`/`REJECT`) |

### Query Object (BlockedQuery Entity)

```json
{
  "id": 101,
  "connId": "conn-1",
  "queryType": "SIMPLE",
  "queryPreview": "UPDATE users SET role = 'ADMIN' WHERE id = 5",
  "status": "PENDING",
  "createdAt": "2026-03-06T12:00:00Z",
  "resolvedAt": null,
  "resolvedBy": null,
  "requiresPeerApproval": true,
  "approvalCount": 1,
  "rejectionCount": 0,
  "requiredApprovals": 2,
  "riskScore": 0.75,
  "syntaxScore": 0.3,
  "dataScore": 0.8,
  "behaviorScore": 0.5,
  "contextScore": 0.2,
  "nonce": "a1b2c3d4-uuid"
}
```

**Field Reference:**

| Field | Type | Description |
| --- | --- | --- |
| `id` | Long | Auto-generated primary key |
| `connId` | String | Proxy connection identifier (e.g. `conn-1`) |
| `queryType` | Enum | `SIMPLE` or `EXTENDED` (wire protocol type) |
| `queryPreview` | String | SQL text (truncated to 4000 chars) |
| `status` | Enum | `PENDING`, `APPROVED`, `REJECTED`, or `EXPIRED` |
| `createdAt` | Instant | Timestamp when query was intercepted |
| `resolvedAt` | Instant | Timestamp when query was resolved (null if pending) |
| `resolvedBy` | String | Username who resolved, or `"Peer Approval System"` |
| `requiresPeerApproval` | boolean | Whether peer voting is active for this query |
| `approvalCount` | int | Current number of APPROVE votes |
| `rejectionCount` | int | Current number of REJECT votes |
| `requiredApprovals` | int | Dynamically computed threshold T(R) |
| `riskScore` | Double | Overall risk score R ∈ [0.0, 1.0] (null if DRS disabled) |
| `syntaxScore` | Double | S_syn component |
| `dataScore` | Double | S_data component |
| `behaviorScore` | Double | S_beh component |
| `contextScore` | Double | S_ctx component |
| `nonce` | String | Unique replay protection nonce (generated at interception) |

### Approve/Reject Request

```json
{
  "id": 101,
  "nonce": "unique-random-string",
  "timestamp": "1738855200000"
}
```

Fields: `id` (`@NotNull`), `nonce` (optional), `timestamp` (optional).

### Approve/Reject Response

```json
{
  "success": true,
  "data": { "success": true },
  "error": null
}
```

Returns `{ "success": false }` in `data` if the query is not found or not in pending status.

### Vote Request

```json
{
  "id": 101,
  "vote": "APPROVE",
  "nonce": "unique-random-string",
  "timestamp": "1738855200000"
}
```

Fields: `id` (`@NotNull`), `vote` (`@NotNull`, `APPROVE` or `REJECT`), `nonce` (optional), `timestamp` (optional).

### Vote Response (Normal)

```json
{
  "success": true,
  "data": {
    "success": true,
    "duplicate": false,
    "autoResolved": false,
    "approvalCount": 1,
    "rejectionCount": 0
  },
  "error": null
}
```

### Vote Response (Auto-Resolved — Threshold Met)

When the approval or rejection count reaches `requiredApprovals`, the query is automatically resolved:

```json
{
  "success": true,
  "data": {
    "success": true,
    "duplicate": false,
    "autoResolved": true,
    "action": "approved"
  },
  "error": null
}
```

The `action` field is `"approved"` or `"rejected"` depending on which threshold was met.

### Vote Response (Duplicate)

HTTP 403:

```json
{
  "success": false,
  "data": null,
  "error": "You have already voted on this query"
}
```

### Vote Response (Changed)

Users can change their vote from `APPROVE` to `REJECT` or vice versa. Changed votes update in-place without returning a duplicate error.

### Vote Status Response

`GET /api/blocked/{id}/votes`

```json
{
  "success": true,
  "data": {
    "id": 101,
    "approvals": ["peer1", "peer2"],
    "rejections": ["peer3"],
    "approvalCount": 2,
    "rejectionCount": 1
  },
  "error": null
}
```

Returns HTTP 404 if the query is not found in the in-memory pending store.

### Behavior Notes

- Replay protection applies when both `nonce` and `timestamp` are provided. The nonce window is 5 minutes, backed by Redis TTL keys.
- Duplicate votes (same user, same vote value) return HTTP 403 with `error` message.
- Vote changes (same user, different vote value) are allowed and update in-place.
- `requiredApprovals` is computed by DRS at interception time and stored for audit purposes.
- The dynamic max approvals is capped at `min(config.maxApprovals, registeredPeerCount)`.
- Auto-resolution: when total approvals ≥ `requiredApprovals`, the query is automatically approved. When total rejections ≥ `requiredApprovals`, it is automatically rejected. The resolver is recorded as `"Peer Approval System"`.

### Compatibility Notes

- Canonical paths are `/api/blocked*`.
- Frontend still tolerates legacy fallback aliases `/api/pending*`.

---

## Module 3: User Management

Admin-only user lifecycle.

### Endpoints

| Method | Path | Auth |
| --- | --- | --- |
| `GET` | `/api/users` | `ADMIN` |
| `POST` | `/api/users` | `ADMIN` |
| `DELETE` | `/api/users/{id}` | `ADMIN` |

### Create User Request

```json
{
  "username": "peer-user",
  "password": "strong-password",
  "role": "PEER"
}
```

Fields: `username` (`@NotBlank`), `password` (`@NotBlank`), `role` (`@NotBlank`).

Current allowed roles: `ADMIN`, `PEER`.

### Create User Response

```json
{
  "success": true,
  "data": {
    "id": 2,
    "username": "peer-user",
    "role": "PEER",
    "createdAt": "2026-03-06T12:10:00Z",
    "lastLogin": null
  },
  "error": null
}
```

### Delete User Response

```json
{
  "success": true,
  "data": { "success": true },
  "error": null
}
```

### Error Cases

| Condition | HTTP Status | Error Message |
| --- | --- | --- |
| Username already exists | 400 | `"Try a different username"` |
| Invalid role string | 400 | `"Invalid role"` |
| User not found | 404 | `"User does not exist"` |
| Admin deleting themselves | 400 | `"Invalid"` |

### Behavior Notes

- Admins cannot delete their own account (self-deletion protection).
- Role is parsed case-insensitively (`"peer"` → `PEER`).
- Error messages are sanitized by `GlobalExceptionHandler` to avoid leaking internal details.

---

## Module 4: Configuration

Proxy configuration snapshot and update acknowledgment.

### Endpoints

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| `GET` | `/api/config` | Authenticated | Returns effective config snapshot |
| `PUT` | `/api/config` | `ADMIN` | Acknowledges request; restart needed |

### Get Config Response (Current)

```json
{
  "success": true,
  "data": {
    "proxy_port": 5432,
    "target_host": "localhost",
    "target_port": 5433,
    "block_by_default": true,
    "critical_keywords": "DROP, ALTER, TRUNCATE, DELETE, GRANT, REVOKE, UPDATE, INSERT",
    "allowed_keywords": "SELECT, CREATE",
    "peer_approval_enabled": true,
    "peer_approval_min_votes": 2
  },
  "error": null
}
```

Note: The `GET` endpoint is accessible to any authenticated user. The `PUT` endpoint requires `ADMIN` role (enforced via `@PreAuthorize`).

### Update Config Response (Current)

```json
{
  "success": true,
  "data": {
    "ok": true,
    "message": "Configuration saved.  Restart required to apply changes."
  },
  "error": null
}
```

Note: The update endpoint does not currently persist changes. It only audits the attempt and returns an acknowledgment.

---

## Module 5: Metrics

Live counters for dashboard visibility.

### Endpoints

| Method | Path | Auth |
| --- | --- | --- |
| `GET` | `/api/metrics` | Authenticated |

### Response (Current)

```json
{
  "success": true,
  "data": {
    "totalConnections": 20,
    "activeConnections": 3,
    "totalQueries": 450,
    "blockedQueries": 32,
    "approvedQueries": 20,
    "rejectedQueries": 12,
    "errors": 1,
    "queryTypes": {
      "SIMPLE": 200,
      "EXTENDED": 140
    }
  },
  "error": null
}
```

Note: `queryTypes` keys reflect the PostgreSQL wire protocol type (`SIMPLE` or `EXTENDED`), not SQL operation types.

### Behavior Notes

- Metrics are in-memory atomic counters, not persisted. They reset on application restart.
- Metrics are logged to the application log every 60 seconds via `@Scheduled`.

### Compatibility Notes

- Legacy clients may use snake_case metrics keys.
- Frontend currently normalizes both camelCase and snake_case variants.

---

## Module 6: Audit Logs

Administrative activity and security trail.

### Endpoints

| Method | Path | Auth |
| --- | --- | --- |
| `GET` | `/api/audit` | `ADMIN` |
| `GET` | `/api/audit/user/{username}` | `ADMIN` |

### Log Object

```json
{
  "id": 505,
  "username": "admin",
  "action": "query_approved",
  "details": "Query #101 approved: UPDATE users... (Type: SIMPLE, Risk: 0.750)",
  "ipAddress": "192.168.1.50",
  "timestamp": "2026-03-06T12:20:00Z",
  "requestHash": null
}
```

### Audit Action Types

| Action | Source | Description |
| --- | --- | --- |
| `login` | AuthController | Successful login |
| `logout` | AuthController | Successful logout |
| `query_blocked` | BlockedQueryService | Query intercepted and blocked |
| `query_approved` | QueryController / BlockedQueryService | Query approved |
| `query_rejected` | QueryController / BlockedQueryService | Query rejected |
| `query_vote` | QueryController / BlockedQueryService | Peer vote cast |
| `replay_attack_blocked` | QueryController | Replay attack detected on approve/reject/vote |
| `replay_attack_detected` | ReplayProtectionService | Duplicate nonce detected |
| `user_created` | UserController | New user created |
| `user_deleted` | UserController | User deleted |
| `config_accessed` | ConfigController | Configuration viewed |
| `config_update_attempted` | ConfigController | Configuration update requested |
| `pending_queries_accessed` | QueryController | Pending queries list viewed |
| `all_queries_accessed` | QueryController | All queries list viewed |
| `vote_status_accessed` | QueryController | Vote status viewed |
| `users_list_accessed` | UserController | User list viewed |
| `audit_logs_accessed` | AuditController | Audit logs viewed |
| `audit_logs_user_accessed` | AuditController | User-specific audit logs viewed |
| `metrics_accessed` | MetricsController | Metrics viewed |
| `admin_user_created` | InterceptorApplication | Default admin created on startup |
| `proxy_client_connected` | ClientHandler | PostgreSQL client connected to proxy |
| `proxy_client_disconnected` | ClientHandler | PostgreSQL client disconnected |
| `proxy_client_error` | ClientHandler | Client connection error |
| `proxy_backend_connected` | ClientHandler | Proxy connected to backend PostgreSQL |
| `proxy_backend_connection_failed` | ClientHandler | Backend connection failure |
| `auth_token_version_mismatch` | JwtAuthFilter | Token version mismatch (invalidated token used) |
| `auth_user_not_found` | JwtAuthFilter | Token contains non-existent user |
| `auth_invalid_token` | JwtAuthFilter | Invalid JWT token presented |

### Behavior Notes

- `GET /api/audit` returns the most recent 100 logs.
- `GET /api/audit/user/{username}` returns all logs for a specific user, newest first.
- Audit logs older than `audit.retention-days` (default: 90) are automatically cleaned up at 2:00 AM daily.
- The `requestHash` field is used for replay protection auditing (populated by `AuditService.logWithHash`).

---

## Module 7: Real-time WebSocket API

STOMP over SockJS endpoint for live updates.

### Connection

- URL: `/ws`
- STOMP CONNECT header: `Authorization: Bearer <token>`

### Topics

| Topic | Purpose |
| --- | --- |
| `/topic/blocked` | New blocked query event |
| `/topic/approvals` | Approval/rejection status event |
| `/topic/votes` | Vote cast event |
| `/topic/logs` | Audit event |
| `/topic/metrics` | Metrics update |

### Payload Shape

Topics may publish either:

- Direct event object
- Wrapped event object with `data` property

Frontend should handle both shapes.

### Compatibility Notes

- Legacy topic alias `/topic/queries` may still be produced/consumed by older clients.
- Current dashboard subscribes to both `/topic/blocked` and `/topic/queries` for compatibility.

---

## Module 8: Dynamic Risk Scoring (Internal)

Dynamic Risk Scoring is an internal service that computes risk scores and required approval counts. It is not directly exposed via REST API but influences the Query Workflow module.

### Risk Assessment Components

The DRS service computes four sub-scores:

1. **Syntax Score (S_syn)**: Analyzes SQL complexity via JSqlParser AST (subquery depth × `depthCoefficient`, join count × `joinCoefficient`)
2. **Data Score (S_data)**: Scores based on table/column sensitivity mappings from `risk-scoring.sensitivity-map`
3. **Behavior Score (S_beh)**: Tracks query patterns per connection (anomaly detection)
4. **Context Score (S_ctx)**: Scores based on IP trustworthiness (trusted prefix matching) and time-of-day (business hours)

### Risk Formula

```
R = min(1.0, w1*S_syn + w2*S_data + w3*S_beh + w4*S_ctx)

Dynamic max: T_max_eff = min(config.maxApprovals, registeredPeerCount)
T_max_eff   = max(config.minApprovals, T_max_eff)

T(R) = ceil(T_min + (T_max_eff - T_min) * R^gamma)
T(R) = clamp(T(R), T_min, T_max_eff)
```

Where:
- `R` is the overall risk score ∈ [0.0, 1.0]
- `T(R)` is the required number of approvals
- `w1, w2, w3, w4` are configurable weights (must sum to 1.0)
- `T_min` is the minimum required approvals (config: `risk-scoring.min-approvals`)
- `T_max_eff` is the effective maximum, capped by actual registered PEER count
- `gamma` is the gamma parameter for the threshold function

### DRS Fallback

When `risk-scoring.enabled` is `false`, the system falls back to the static `approval.min-votes` from configuration.

### Configuration

DRS is configured via `risk-scoring.*` properties in `application-dev.yaml`:

- `risk-scoring.enabled`: Enable/disable DRS
- `risk-scoring.syntax-weight`: Weight for syntax complexity (default: 0.2)
- `risk-scoring.data-weight`: Weight for data sensitivity (default: 0.4)
- `risk-scoring.behavior-weight`: Weight for behavioral patterns (default: 0.3)
- `risk-scoring.context-weight`: Weight for context factors (default: 0.1)
- `risk-scoring.min-approvals`: Minimum required approvals (default: 1)
- `risk-scoring.max-approvals`: Maximum required approvals (default: 20)
- `risk-scoring.gamma`: Gamma parameter for threshold function (default: 2.0)
- `risk-scoring.depth-coefficient`: Penalty coefficient for subquery depth (default: 0.15)
- `risk-scoring.join-coefficient`: Penalty coefficient for join count (default: 0.2)
- `risk-scoring.sensitivity-map`: Table/column sensitivity scores (map of name → score)
- `risk-scoring.business-hour-start`: Start of business hours (default: 9)
- `risk-scoring.business-hour-end`: End of business hours (default: 18)
- `risk-scoring.trusted-ip-prefixes`: Trusted IP prefixes (default: ["10.", "172.16.", "192.168.", "127."])

### Risk Assessment Record (Internal)

```java
public record RiskAssessment(
    double riskScore,         // Overall risk score R ∈ [0.0, 1.0]
    int requiredApprovals,   // Computed threshold T(R)
    double syntaxScore,       // S_syn component
    double dataScore,         // S_data component
    double behaviorScore,    // S_beh component
    double contextScore       // S_ctx component
) {}
```

---

## Security and Access Matrix

| Area | Access Rule |
| --- | --- |
| Public | Static assets, `/ws/**`, `/api/login`, `/api/logout`, `/v3/api-docs/**`, `/swagger-ui/**`, `/actuator/health` |
| Admin-only | `/api/users/**`, `/api/config/**` (PUT), `/api/audit/**`, `/api/approve`, `/api/reject` |
| Admin/Peer | `/api/blocked/**`, `/api/vote` |
| Authenticated | `/api/config` (GET), `/api/metrics` |

Security is enforced at two levels:
1. **URL-based**: `SecurityConfig` filter chain with `requestMatchers` rules
2. **Method-based**: `@PreAuthorize("hasRole('ADMIN')")` annotations on `AuditController`, `UserController`, and `ConfigController.updateConfig()`

---

## Error Handling

All errors are handled by `GlobalExceptionHandler` (`@RestControllerAdvice`):

| Exception | HTTP Status | Response |
| --- | --- | --- |
| `EntityNotFoundException` | 404 | `{ "success": false, "error": "<message>" }` |
| `IllegalArgumentException` | 400 | `{ "success": false, "error": "<sanitized message>" }` |

Error message sanitization:
- `"No enum constant..."` → `"Invalid role"`
- `"Username already exists"` → `"Try a different username"`
- All other `IllegalArgumentException` → `"Invalid"`

---

## Deprecation and Reversibility Policy

When changing endpoints or payloads:

1. Keep current canonical API stable.
2. Add compatibility aliases for one release window.
3. Document alias in this file under the module's compatibility notes.
4. Remove alias after migration and update module table only.

This keeps feature additions/removals reversible and isolated.

---

## Test Snippets

### Check pending blocked queries

```bash
curl -H "Authorization: Bearer <token>" https://localhost/api/blocked
```

### Approve blocked query

```bash
curl -X POST https://localhost/api/approve \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"id":101}'
```

### Reject blocked query

```bash
curl -X POST https://localhost/api/reject \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"id":101}'
```

### Vote on query (peer mode)

```bash
curl -X POST https://localhost/api/vote \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"id":101,"vote":"APPROVE"}'
```

### Get vote status

```bash
curl -H "Authorization: Bearer <token>" https://localhost/api/blocked/101/votes
```

### Get metrics

```bash
curl -H "Authorization: Bearer <token>" https://localhost/api/metrics
```

### Get audit logs

```bash
curl -H "Authorization: Bearer <token>" https://localhost/api/audit
```

### Get user audit logs

```bash
curl -H "Authorization: Bearer <token>" https://localhost/api/audit/user/admin
```

### Get configuration

```bash
curl -H "Authorization: Bearer <token>" https://localhost/api/config
```

### Update configuration

```bash
curl -X PUT https://localhost/api/config \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"proxy_port":5432,"target_host":"localhost","target_port":5433,"block_by_default":true,"critical_keywords":"DROP,ALTER,TRUNCATE","allowed_keywords":"SELECT,CREATE","peer_approval_enabled":true,"peer_approval_min_votes":2}'
```

### Create user

```bash
curl -X POST https://localhost/api/users \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"username":"peer-user","password":"strong-password","role":"PEER"}'
```

### Delete user

```bash
curl -X DELETE https://localhost/api/users/2 \
  -H "Authorization: Bearer <token>"
```

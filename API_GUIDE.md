# Interceptor Backend API Guide

This guide is the contract reference for frontend and integration clients.

## How to Use This Guide

The API is documented by feature module. To add/remove features safely:

1. Add/remove one module section.
2. Update only that section's endpoint table and payload examples.
3. Update compatibility notes for aliases/deprecations.

---

## Module 1: Authentication

JWT-based stateless authentication.

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

### Login Response (Current)

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9..."
}
```

### Token Claims Used by Frontend

- `sub` -> username
- `role` -> role (`ADMIN` or `PEER`)
- `token_version` -> session invalidation support

### Compatibility Notes

- Some clients may still expect a `user` object from login. The current backend returns token-only.
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
| `GET` | `/api/blocked/all` | `ADMIN` or `PEER` | Full query history |
| `GET` | `/api/blocked/{id}/votes` | `ADMIN` or `PEER` | Vote status details |
| `POST` | `/api/approve` | `ADMIN` or `PEER` | Direct approve path |
| `POST` | `/api/reject` | `ADMIN` or `PEER` | Direct reject path |
| `POST` | `/api/vote` | `ADMIN` or `PEER` | Vote path (`APPROVE`/`REJECT`) |

### Query Object (Representative)

```json
{
  "id": 101,
  "connId": "db-connection-1",
  "queryType": "UPDATE",
  "queryPreview": "UPDATE users SET role = 'ADMIN' WHERE id = 5",
  "status": "PENDING",
  "createdAt": "2026-03-06T12:00:00Z",
  "resolvedAt": null,
  "resolvedBy": null,
  "requiresPeerApproval": true,
  "approvalCount": 1,
  "rejectionCount": 0
}
```

### Approve/Reject Request

```json
{
  "id": 101,
  "nonce": "unique-random-string",
  "timestamp": "1738855200000"
}
```

### Vote Request

```json
{
  "id": 101,
  "vote": "APPROVE",
  "nonce": "unique-random-string",
  "timestamp": "1738855200000"
}
```

Allowed `vote` values: `APPROVE`, `REJECT`.

### Behavior Notes

- Replay protection applies when both `nonce` and `timestamp` are provided.
- Duplicate votes can return `403` with details (`duplicate: true`).
- Vote status endpoint returns aggregated counts and may include voter username lists.

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

Current allowed roles: `ADMIN`, `PEER`.

### User Response (Representative)

```json
{
  "id": 2,
  "username": "peer-user",
  "role": "PEER",
  "createdAt": "2026-03-06T12:10:00Z",
  "lastLogin": null
}
```

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
  "proxy_port": 5432,
  "target_host": "localhost",
  "target_port": 5433,
  "block_by_default": true,
  "critical_keywords": "DROP, ALTER, TRUNCATE",
  "allowed_keywords": "SELECT, CREATE",
  "peer_approval_enabled": true,
  "peer_approval_min_votes": 2
}
```

### Update Config Response (Current)

```json
{
  "ok": true,
  "message": "Configuration saved.  Restart required to apply changes."
}
```

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
  "totalConnections": 20,
  "activeConnections": 3,
  "totalQueries": 450,
  "blockedQueries": 32,
  "approvedQueries": 20,
  "rejectedQueries": 12,
  "errors": 1,
  "queryTypes": {
    "SELECT": 200,
    "UPDATE": 140
  }
}
```

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

### Log Object (Representative)

```json
{
  "id": 505,
  "username": "admin",
  "action": "query_approved",
  "details": "Query #101 approved",
  "ipAddress": "192.168.1.50",
  "timestamp": "2026-03-06T12:20:00Z"
}
```

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

## Security and Access Matrix

| Area | Access Rule |
| --- | --- |
| Public | Static assets, `/ws/**`, `/api/login`, `/api/logout`, health/docs routes |
| Admin-only | `/api/users/**`, `/api/config/**`, `/api/audit/**` |
| Admin/Peer | `/api/blocked/**`, `/api/approve`, `/api/reject`, `/api/vote` |
| Other | Authenticated |

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

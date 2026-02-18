# Interceptor Backend API Guide

This document serves as a comprehensive reference for the Interceptor Backend API. It is intended for frontend developers building client interfaces and for developers integrating with the system directly.

The Interceptor application exposes two main interfaces:

1. **REST API**: For administration, query approval workflows, user management, and configuration.
2. **WebSocket API**: For real-time updates on intercepted queries and approval status.
3. **TCP Proxy**: The database proxy itself, which clients connect to via standard PostgreSQL drivers.

---

## 1. Authentication

The API uses stateless **JWT (JSON Web Token)** authentication.

### Login

Authenticate a user to receive an access token.

- **Endpoint**: `POST /api/login`
- **Access**: Public
- **Request Body**:
  ```json
  {
    "username": "your_username",
    "password": "your_password"
  }
  ```
- **Response (200 OK)**:
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "user": {
      "id": 1,
      "username": "admin",
      "role": "ADMIN"
    }
  }
  ```

### Logout

- **Endpoint**: `POST /api/logout`
- **Access**: Authenticated
- **Response**: `{"ok": true}`

### Authorization Header

For all subsequent requests to protected endpoints, include the JWT in the `Authorization` header:

```http
Authorization: Bearer <your_token>
```

---

## 2. Query Management

These endpoints control the core approval workflow for intercepted database queries.

**Roles Required**: `ADMIN` or `PEER`

### Get Pending Queries

Retrieve all queries currently blocked and awaiting decision.

- **Endpoint**: `GET /api/blocked`
- **Response**:
  ```json
  [
    {
      "id": 101,
      "connId": "db-connection-1",
      "queryType": "UPDATE",
      "queryPreview": "UPDATE users SET role = 'ADMIN' WHERE id = 5",
      "status": "PENDING",
      "createdAt": "2023-10-27T10:00:00Z",
      "requiresPeerApproval": true,
      "approvalCount": 1,
      "rejectionCount": 0
    }
  ]
  ```

### Get All Queries

Retrieve history of all intercepted queries (pending, approved, rejected).

- **Endpoint**: `GET /api/blocked/all`

### Approve Query

Approve a specific pending query.

- **Endpoint**: `POST /api/approve`
- **Request Body**:
  ```json
  {
    "id": 101,
    "nonce": "unique-random-string", // Optional: for replay protection
    "timestamp": "1698400000000" // Optional: for replay protection
  }
  ```
- **Response**: `{"success": true}`

### Reject Query

Reject a pending query.

- **Endpoint**: `POST /api/reject`
- **Request Body**: Same structure as Approve.

### Cast Vote

For workflows requiring peer consensus.

- **Endpoint**: `POST /api/vote`
- **Request Body**:
  ```json
  {
    "id": 101,
    "vote": "APPROVE",
    "nonce": "unique-random-string",
    "timestamp": "1698400000000"
  }
  ```
  _Allowed Votes_: `APPROVE`, `REJECT`

### Get Vote Status

Check the current voting status of a query.

- **Endpoint**: `GET /api/blocked/{id}/votes`

---

## 3. User Management

Manage platform users and their roles.

**Role Required**: `ADMIN`

### List Users

- **Endpoint**: `GET /api/users`
- **Response**: List of user objects.

### Create User

- **Endpoint**: `POST /api/users`
- **Request Body**:
  ```json
  {
    "username": "newuser",
    "password": "securePass123",
    "role": "PEER"
  }
  ```
  _Allowed Roles_: `ADMIN`, `PEER`, `OBSERVER`

### Delete User

- **Endpoint**: `DELETE /api/users/{id}`

---

## 4. System Configuration & Metrics

Monitor and configure the proxy behavior.

### Get Configuration

- **Endpoint**: `GET /api/config`
- **Response**:
  ```json
  {
    "proxy_port": 5432,
    "target_host": "localhost",
    "block_by_default": true,
    "critical_keywords": "DROP,DELETE,UPDATE",
    "peer_approval_enabled": true
  }
  ```

### Update Configuration

- **Endpoint**: `PUT /api/config`
- **Note**: Some changes may require a server restart.

### Get Metrics

Retrieve system performance and usage metrics.

- **Endpoint**: `GET /api/metrics`
- **Response**:
  ```json
  {
    "queries_intercepted": 1250,
    "queries_blocked": 45,
    "active_connections": 12,
    "uptime_seconds": 3600
  }
  ```

---

## 5. Audit Logs

View security and activity logs for compliance.

**Role Required**: `ADMIN`

### Get Recent Logs

- **Endpoint**: `GET /api/audit`
- **Response**:
  ```json
  [
    {
      "id": 505,
      "username": "admin",
      "action": "query_approved",
      "details": "Query #101 approved",
      "ipAddress": "192.168.1.50",
      "timestamp": "2023-10-27T10:05:00Z"
    }
  ]
  ```

### Get Logs by User

- **Endpoint**: `GET /api/audit/user/{username}`

---

## 6. Real-time WebSocket API

The API supports real-time updates using **STOMP over WebSocket** (with SockJS support). This is critical for the live dashboard.

**Connection URL**: `http://<host>:8080/ws`
**Protocol**: STOMP v1.1/1.2

### Connection & Authentication

The WebSocket connection mandates authentication during the initial Handshake or CONNECT frame.
Pass the JWT token in the `Authorization` header of the STOMP CONNECT frame.

```
CONNECT
Authorization:Bearer <your_token>
accept-version:1.1,1.0
heart-beat:10000,10000
```

### Topics (Subscriptions)

Clients should subscribe to these topics to receive live updates:

1.  **`/topic/queries`**
    - **Trigger**: A new query is intercepted and blocked by the proxy.
    - **Payload**:
      ```json
      {
        "type": "NEW_QUERY",
        "data": {
          "id": 105,
          "queryPreview": "DELETE FROM orders WHERE id < 1000",
          "status": "PENDING"
        }
      }
      ```

2.  **`/topic/approvals`**
    - **Trigger**: A query status changes (Approved/Rejected/Vote Cast).
    - **Payload**:
      ```json
      {
        "type": "STATUS_UPDATE",
        "data": {
          "queryId": 105,
          "status": "APPROVED",
          "approvedBy": "admin"
        }
      }
      ```

---

## 7. Testing Guide using PostgreSQL (`psql`)

This guide explains how to verify the Interceptor is working correctly by connecting to it as a standard PostgreSQL client.

### Prerequisites

1.  **Interceptor Running**: Ensure the Spring Boot application is running (e.g., via `mvn spring-boot:run` or Docker).
2.  **Target Database**: A real PostgreSQL instance must be running on the configured target port (default: 5433).
3.  **Client Tool**: `psql` (PostgreSQL command line tool) installed.

### Step 1: Connect to the Proxy

Instead of connecting directly to your database (port 5433), connect to the **Interceptor Proxy Port** (default: 5432).

```bash
# Connect to the Interceptor Proxy (acting as Postgres)
psql -h localhost -p 5432 -U postgres -d postgres
```

_Note: Depending on your config, the username/password should match your backend target database._

### Step 2: Test "Allowed" Queries

By default, `SELECT` queries are often configured as safe. Run a query that should pass through immediately.

```sql
postgres=# SELECT 1;
 ?column?
----------
        1
(1 row)
```

**Result**: The query should return immediately without hanging.

### Step 3: Test "Critical" Queries (Blocking)

Run a query containing a configured critical keyword (e.g., `UPDATE`, `DELETE`, `DROP`).

```sql
postgres=# UPDATE users SET role = 'admin' WHERE id = 1;
```

**Observation**:

- The terminal running `psql` will **hang**. It is waiting for a response from the proxy.
- The command has been intercepted and is awaiting approval.

### Step 4: Verify & Approve

1.  **Check API**: Use `curl` or Postman to see the blocked query.

    ```bash
    curl -H "Authorization: Bearer <your_token>" http://localhost:8080/api/blocked
    ```

    You should see an entry with `status: "PENDING"`.

2.  **Approve**: Send an approval request.

    ```bash
    curl -X POST http://localhost:8080/api/approve \
         -H "Authorization: Bearer <your_token>" \
         -H "Content-Type: application/json" \
         -d '{"id": <QUERY_ID>}'
    ```

3.  **Check `psql`**:
    - Switch back to your `psql` terminal.
    - The query that was hanging should now complete successfully.
    ```
    UPDATE 1
    postgres=#
    ```

### Step 5: Test Rejection

Repeat Step 3, but this time use the `/api/reject` endpoint.

- **Result**: The `psql` client will receive an error message:
  ```
  ERROR: Query rejected by administrator.
  ```

### Troubleshooting

- **Connection Refused**: Ensure Interceptor is running and listening on port 5432.
- **Authentication Failed**: Verify usage of correct Postgres credentials (the proxy passes authentication through to the real DB).
- **No Block**: Check `application.yaml` to see if the keyword is actually in the `critical-keywords` list or if `block-by-default` is false.

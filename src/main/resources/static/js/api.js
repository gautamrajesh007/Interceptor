/* ═══════════════════════════════════════════════════════════
   Interceptor Dashboard — API Client & WebSocket Manager
   ═══════════════════════════════════════════════════════════ */

const API = (() => {
  // Base URL — empty string when served from the same origin
  const BASE = "";

  // ─── Token Management ───
  function getToken() {
    return localStorage.getItem("interceptor_token");
  }

  function setToken(token) {
    localStorage.setItem("interceptor_token", token);
  }

  function clearToken() {
    localStorage.removeItem("interceptor_token");
    localStorage.removeItem("interceptor_user");
  }

  function getUser() {
    try {
      const cached = JSON.parse(localStorage.getItem("interceptor_user"));
      if (cached) return cached;
    } catch {
      // Ignore parse errors and try deriving from token
    }

    const token = getToken();
    if (!token) return null;

    const derived = deriveUserFromToken(token);
    if (derived) setUser(derived);
    return derived;
  }

  function setUser(user) {
    localStorage.setItem("interceptor_user", JSON.stringify(user));
  }

  function decodeJwtPayload(token) {
    try {
      const parts = token.split(".");
      if (parts.length < 2) return null;
      const payload = parts[1].replace(/-/g, "+").replace(/_/g, "/");
      const decoded = atob(payload);
      return JSON.parse(decoded);
    } catch {
      return null;
    }
  }

  function deriveUserFromToken(token, fallbackUsername) {
    const payload = decodeJwtPayload(token);
    if (!payload) return null;

    const username = payload.sub || payload.username || fallbackUsername;
    const role = payload.role || payload.authorities || "PEER";
    if (!username) return null;

    return {
      id: payload.id || null,
      username,
      role: Array.isArray(role) ? role[0] : role,
    };
  }

  // ─── HTTP Helpers ───
  async function request(method, path, body = null, requiresAuth = true) {
    const headers = { "Content-Type": "application/json" };

    if (requiresAuth) {
      const token = getToken();
      if (!token) throw new Error("Not authenticated");
      headers["Authorization"] = `Bearer ${token}`;
    }

    const opts = { method, headers };
    if (body) opts.body = JSON.stringify(body);

    const res = await fetch(`${BASE}${path}`, opts);

    // Handle 401/403 → force logout
    if (res.status === 401 || res.status === 403) {
      const data = await res.json().catch(() => ({}));
      if (res.status === 401) {
        clearToken();
        window.dispatchEvent(new Event("auth:expired"));
      }
      throw { status: res.status, ...data };
    }

    if (!res.ok) {
      const data = await res.json().catch(() => ({ error: res.statusText }));
      // If the backend returned an ApiResponse error format, unwrap it
      if (data && typeof data.success === "boolean" && data.error) {
        throw { status: res.status, error: data.error };
      }
      throw { status: res.status, ...data };
    }

    const text = await res.text();
    const parsed = text ? JSON.parse(text) : {};

    // Auto-unwrap ApiResponse standard format
    if (parsed && typeof parsed.success === "boolean" && ('data' in parsed || 'error' in parsed)) {
      if (!parsed.success) {
        throw { status: res.status, error: parsed.error || "Unknown API error" };
      }
      return parsed.data !== null && parsed.data !== undefined ? parsed.data : {};
    }

    return parsed;
  }

  // ─── Auth Endpoints ───
  async function login(username, password) {
    const data = await request(
      "POST",
      "/api/login",
      { username, password },
      false,
    );

    const token = data.token || data.accessToken;
    if (token) {
      setToken(token);
      const user = data.user || deriveUserFromToken(token, username);
      if (user) setUser(user);
    }
    return data;
  }

  async function logout() {
    try {
      await request("POST", "/api/logout");
    } catch {
      // Ignore errors on logout
    }
    clearToken();
  }

  // ─── Query Endpoints ───
  async function getBlockedQueries() {
    try {
      return await request("GET", "/api/blocked");
    } catch (err) {
      if (err && err.status === 404) {
        return request("GET", "/api/pending");
      }
      throw err;
    }
  }

  async function getAllQueries() {
    try {
      return await request("GET", "/api/blocked/all");
    } catch (err) {
      if (err && err.status === 404) {
        return request("GET", "/api/pending/all");
      }
      throw err;
    }
  }

  function approveQuery(id) {
    const nonce = crypto.randomUUID
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random()}`;
    return request("POST", "/api/approve", {
      id,
      nonce,
      timestamp: String(Date.now()),
    });
  }

  function rejectQuery(id) {
    const nonce = crypto.randomUUID
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random()}`;
    return request("POST", "/api/reject", {
      id,
      nonce,
      timestamp: String(Date.now()),
    });
  }

  function voteQuery(id, vote) {
    const nonce = crypto.randomUUID
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random()}`;
    return request("POST", "/api/vote", {
      id,
      vote,
      nonce,
      timestamp: String(Date.now()),
    });
  }

  function getVoteStatus(id) {
    return request("GET", `/api/blocked/${id}/votes`);
  }

  // ─── User Endpoints ───
  function getUsers() {
    return request("GET", "/api/users");
  }

  function createUser(username, password, role) {
    return request("POST", "/api/users", { username, password, role });
  }

  function deleteUser(id) {
    return request("DELETE", `/api/users/${id}`);
  }

  // ─── Config Endpoints ───
  function getConfig() {
    return request("GET", "/api/config");
  }

  function updateConfig(config) {
    return request("PUT", "/api/config", config);
  }

  // ─── Metrics ───
  function getMetrics() {
    return request("GET", "/api/metrics");
  }

  // ─── Audit ───
  function getAuditLogs() {
    return request("GET", "/api/audit");
  }

  function getAuditLogsByUser(username) {
    return request("GET", `/api/audit/user/${encodeURIComponent(username)}`);
  }

  // ═══════════════════════════
  //  WebSocket Manager (STOMP)
  // ═══════════════════════════
  let stompClient = null;
  let reconnectTimer = null;
  const listeners = {};

  function on(event, callback) {
    if (!listeners[event]) listeners[event] = [];
    listeners[event].push(callback);
  }

  function off(event, callback) {
    if (!listeners[event]) return;
    listeners[event] = listeners[event].filter((cb) => cb !== callback);
  }

  function emit(event, data) {
    (listeners[event] || []).forEach((cb) => {
      try {
        cb(data);
      } catch (e) {
        console.error("Event handler error:", e);
      }
    });
  }

  function connectWebSocket() {
    const token = getToken();
    if (!token) return;

    // Disconnect any existing connection
    disconnectWebSocket();

    try {
      const socket = new SockJS(`${BASE}/ws`);
      stompClient = Stomp.over(socket);

      // Disable STOMP debug logging in production
      stompClient.debug = null;

      const connectHeaders = {
        Authorization: `Bearer ${token}`,
      };

      const subscribe = (topic, event, transform = (v) => v) => {
        stompClient.subscribe(topic, function (message) {
          try {
            const raw = JSON.parse(message.body);
            const payload = raw && typeof raw === "object" && "data" in raw ? raw.data : raw;
            emit(event, transform(payload || {}));
          } catch (e) {
            console.error("Parse error:", e);
          }
        });
      };

      stompClient.connect(
        connectHeaders,
        function onConnect() {
          emit("ws:connected");
          clearTimeout(reconnectTimer);

          // Support both current and legacy topic names.
          subscribe("/topic/blocked", "query:blocked");
          subscribe("/topic/queries", "query:blocked");
          subscribe("/topic/approvals", "query:approval");
          subscribe("/topic/votes", "query:vote");
          subscribe("/topic/logs", "audit:log");
          subscribe("/topic/metrics", "metrics:update");
        },
        function onError(err) {
          console.warn("STOMP connection error:", err);
          emit("ws:disconnected");
          scheduleReconnect();
        },
      );

      socket.onclose = function () {
        emit("ws:disconnected");
        scheduleReconnect();
      };
    } catch (err) {
      console.error("WebSocket connection failed:", err);
      emit("ws:disconnected");
      scheduleReconnect();
    }
  }

  function disconnectWebSocket() {
    clearTimeout(reconnectTimer);
    if (stompClient) {
      try {
        stompClient.disconnect();
      } catch {
        // Ignore disconnect errors
      }
      stompClient = null;
    }
  }

  function scheduleReconnect() {
    clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(() => {
      if (getToken()) {
        connectWebSocket();
      }
    }, 5000);
  }

  // ─── Public API ───
  return {
    // Auth
    login,
    logout,
    getToken,
    getUser,
    clearToken,

    // Queries
    getBlockedQueries,
    getAllQueries,
    approveQuery,
    rejectQuery,
    voteQuery,
    getVoteStatus,

    // Users
    getUsers,
    createUser,
    deleteUser,

    // Config
    getConfig,
    updateConfig,

    // Metrics
    getMetrics,

    // Audit
    getAuditLogs,
    getAuditLogsByUser,

    // WebSocket
    connectWebSocket,
    disconnectWebSocket,

    // Events
    on,
    off,
    emit,
  };
})();

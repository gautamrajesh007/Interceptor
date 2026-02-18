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
      return JSON.parse(localStorage.getItem("interceptor_user"));
    } catch {
      return null;
    }
  }

  function setUser(user) {
    localStorage.setItem("interceptor_user", JSON.stringify(user));
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
      throw { status: res.status, ...data };
    }

    // Some endpoints may return empty body
    const text = await res.text();
    return text ? JSON.parse(text) : {};
  }

  // ─── Auth Endpoints ───
  async function login(username, password) {
    const data = await request(
      "POST",
      "/api/login",
      { username, password },
      false,
    );
    if (data.token) {
      setToken(data.token);
      setUser(data.user);
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
  function getBlockedQueries() {
    return request("GET", "/api/blocked");
  }

  function getAllQueries() {
    return request("GET", "/api/blocked/all");
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

      stompClient.connect(
        connectHeaders,
        function onConnect() {
          emit("ws:connected");
          clearTimeout(reconnectTimer);

          // Subscribe to all topics
          stompClient.subscribe("/topic/blocked", function (message) {
            try {
              const data = JSON.parse(message.body);
              emit("query:blocked", data);
            } catch (e) {
              console.error("Parse error:", e);
            }
          });

          stompClient.subscribe("/topic/approvals", function (message) {
            try {
              const data = JSON.parse(message.body);
              emit("query:approval", data);
            } catch (e) {
              console.error("Parse error:", e);
            }
          });

          stompClient.subscribe("/topic/votes", function (message) {
            try {
              const data = JSON.parse(message.body);
              emit("query:vote", data);
            } catch (e) {
              console.error("Parse error:", e);
            }
          });

          stompClient.subscribe("/topic/logs", function (message) {
            try {
              const data = JSON.parse(message.body);
              emit("audit:log", data);
            } catch (e) {
              console.error("Parse error:", e);
            }
          });

          stompClient.subscribe("/topic/metrics", function (message) {
            try {
              const data = JSON.parse(message.body);
              emit("metrics:update", data);
            } catch (e) {
              console.error("Parse error:", e);
            }
          });
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

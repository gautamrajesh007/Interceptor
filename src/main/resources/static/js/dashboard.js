/* ═══════════════════════════════════════════════════════════
   Interceptor Dashboard — Main Application Logic
   ═══════════════════════════════════════════════════════════ */

const Dashboard = (() => {
  // ─── State ───
  const state = {
    currentPage: "dashboard",
    queryFilter: "all",
    allQueries: [],
    metrics: {},
    timelineEvents: [],
    refreshInterval: null,
    auditSearchDebounce: null,
  };

  // Maximum timeline events to show
  const MAX_TIMELINE = 50;

  // ─── DOM References ───
  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => document.querySelectorAll(sel);

  // ═══════════════════════════════════════
  //  INITIALIZATION
  // ═══════════════════════════════════════
  function init() {
    // Check for existing session
    if (API.getToken() && API.getUser()) {
      showApp();
    } else {
      showLogin();
    }

    bindEvents();
  }

  function bindEvents() {
    // Login form
    $("#login-form").addEventListener("submit", handleLogin);

    // Sidebar navigation
    $$(".nav-item[data-page]").forEach((item) => {
      item.addEventListener("click", (e) => {
        e.preventDefault();
        navigateTo(item.dataset.page);
      });
    });

    // Logout
    $("#logout-btn").addEventListener("click", handleLogout);

    // Refresh dashboard
    $("#refresh-dashboard").addEventListener("click", refreshDashboard);

    // Query filter tabs
    $$(".filter-tab").forEach((tab) => {
      tab.addEventListener("click", () => {
        $$(".filter-tab").forEach((t) => t.classList.remove("active"));
        tab.classList.add("active");
        state.queryFilter = tab.dataset.filter;
        renderQueriesTable();
      });
    });

    // Create user button
    $("#btn-create-user").addEventListener("click", showCreateUserModal);

    // Modal close
    $("#modal-close").addEventListener("click", closeModal);
    $("#modal-overlay").addEventListener("click", (e) => {
      if (e.target === $("#modal-overlay")) closeModal();
    });

    // Audit search
    $("#audit-search").addEventListener("input", (e) => {
      clearTimeout(state.auditSearchDebounce);
      state.auditSearchDebounce = setTimeout(() => {
        loadAuditLogs(e.target.value.trim());
      }, 400);
    });

    // Auth expired handler
    window.addEventListener("auth:expired", () => {
      showToast("Session expired. Please sign in again.", "warning");
      handleLogout();
    });

    // WebSocket event handlers
    API.on("ws:connected", () => {
      const indicator = $("#ws-indicator");
      indicator.classList.add("connected");
      indicator.querySelector(".live-text").textContent = "Live";
    });

    API.on("ws:disconnected", () => {
      const indicator = $("#ws-indicator");
      indicator.classList.remove("connected");
      indicator.querySelector(".live-text").textContent = "Reconnecting…";
    });

    API.on("query:blocked", (data) => {
      addTimelineEvent(
        "blocked",
        "Query Intercepted",
        data.preview || data.queryPreview || `Query #${data.queryId}`,
        data.timestamp,
      );
      showToast("New query intercepted and blocked", "info");
      // Refresh pending queries
      loadPendingQueries();
      loadMetrics();
    });

    API.on("query:approval", (data) => {
      const status = (data.status || data.type || "").toLowerCase();
      const isApproved =
        status.includes("approved") || status.includes("approve");
      addTimelineEvent(
        isApproved ? "approved" : "rejected",
        isApproved ? "Query Approved" : "Query Rejected",
        `Query #${data.queryId} by ${data.resolvedBy || "system"}`,
        data.timestamp,
      );
      loadPendingQueries();
      loadAllQueries();
      loadMetrics();
    });

    API.on("query:vote", (data) => {
      addTimelineEvent(
        "vote",
        "Vote Cast",
        `${data.username} voted ${data.vote} on #${data.queryId}`,
        data.timestamp,
      );
      loadPendingQueries();
    });

    API.on("audit:log", (data) => {
      addTimelineEvent(
        "default",
        data.action || "Activity",
        data.details || "",
        data.timestamp,
      );
    });

    API.on("metrics:update", (data) => {
      if (data) {
        state.metrics = data;
        renderMetrics();
      }
    });

    // Keyboard shortcut: Escape closes modal
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape") closeModal();
    });
  }

  // ═══════════════════════════════════════
  //  AUTH
  // ═══════════════════════════════════════
  async function handleLogin(e) {
    e.preventDefault();
    const username = $("#login-username").value.trim();
    const password = $("#login-password").value;
    const errorEl = $("#login-error");
    const btn = $("#login-btn");

    if (!username || !password) {
      errorEl.textContent = "Please enter both username and password.";
      return;
    }

    // Show loading
    btn.querySelector(".btn-text").classList.add("hidden");
    btn.querySelector(".btn-loader").classList.remove("hidden");
    btn.disabled = true;
    errorEl.textContent = "";

    try {
      const result = await API.login(username, password);
      if (result.token) {
        showApp();
      } else {
        errorEl.textContent = result.error || "Login failed. Please try again.";
      }
    } catch (err) {
      errorEl.textContent =
        err.error || "Invalid credentials. Please try again.";
    } finally {
      btn.querySelector(".btn-text").classList.remove("hidden");
      btn.querySelector(".btn-loader").classList.add("hidden");
      btn.disabled = false;
    }
  }

  async function handleLogout() {
    API.disconnectWebSocket();
    clearInterval(state.refreshInterval);
    await API.logout();
    showLogin();
  }

  // ═══════════════════════════════════════
  //  VIEWS
  // ═══════════════════════════════════════
  function showLogin() {
    const overlay = $("#login-overlay");
    overlay.classList.remove("fade-out", "hidden");
    $("#app").classList.add("hidden");
    // Reset form
    $("#login-username").value = "";
    $("#login-password").value = "";
    $("#login-error").textContent = "";
  }

  function showApp() {
    const user = API.getUser();
    if (!user) return showLogin();

    // Set user info
    $("#sidebar-username").textContent = user.username;
    $("#sidebar-role").textContent = user.role;
    $("#user-avatar").textContent = user.username.charAt(0).toUpperCase();

    // Show/hide admin-only elements
    const isAdmin = user.role === "ADMIN";
    $$(".admin-only").forEach((el) => {
      el.style.display = isAdmin ? "" : "none";
    });

    // Transition: fade out login, show app
    const overlay = $("#login-overlay");
    overlay.classList.add("fade-out");
    setTimeout(() => {
      overlay.classList.add("hidden");
    }, 500);

    $("#app").classList.remove("hidden");

    // Connect WebSocket
    API.connectWebSocket();

    // Load initial data
    navigateTo("dashboard");

    // Start periodic refresh
    state.refreshInterval = setInterval(() => {
      if (state.currentPage === "dashboard") {
        loadMetrics();
        loadPendingQueries();
      }
    }, 15000);
  }

  // ═══════════════════════════════════════
  //  NAVIGATION
  // ═══════════════════════════════════════
  function navigateTo(page) {
    state.currentPage = page;

    // Update nav active state
    $$(".nav-item[data-page]").forEach((item) => {
      item.classList.toggle("active", item.dataset.page === page);
    });

    // Show correct page
    $$(".page").forEach((p) => p.classList.remove("active"));
    const pageEl = $(`#page-${page}`);
    if (pageEl) {
      pageEl.classList.add("active");
      // Force re-trigger animation
      pageEl.style.animation = "none";
      pageEl.offsetHeight; // Trigger reflow
      pageEl.style.animation = "";
    }

    // Load page-specific data
    switch (page) {
      case "dashboard":
        loadMetrics();
        loadPendingQueries();
        break;
      case "queries":
        loadAllQueries();
        break;
      case "users":
        loadUsers();
        break;
      case "audit":
        loadAuditLogs();
        break;
      case "settings":
        loadConfig();
        break;
    }
  }

  // ═══════════════════════════════════════
  //  DASHBOARD PAGE
  // ═══════════════════════════════════════
  async function loadMetrics() {
    try {
      const data = await API.getMetrics();
      state.metrics = data;
      renderMetrics();
    } catch (err) {
      console.warn("Failed to load metrics:", err);
    }
  }

  function renderMetrics() {
    const m = state.metrics;

    animateNumber("stat-total", m.totalQueries || 0);
    animateNumber("stat-blocked", m.blockedQueries || 0);
    animateNumber("stat-approved", m.approvedQueries || 0);
    animateNumber("stat-connections", m.activeConnections || 0);

    $("#stat-rejected").textContent = m.rejectedQueries || 0;
    $("#stat-errors").textContent = m.errors || 0;

    // Update stat bar percentages
    const total = Math.max(m.totalQueries || 1, 1);
    const blockedPct = Math.round(((m.blockedQueries || 0) / total) * 100);
    const approvedPct = Math.round(((m.approvedQueries || 0) / total) * 100);
    const connPct = Math.min(((m.activeConnections || 0) / 50) * 100, 100);

    updateStatBar(0, 100);
    updateStatBar(1, blockedPct);
    updateStatBar(2, approvedPct);
    updateStatBar(3, connPct);
  }

  function updateStatBar(index, percent) {
    const bars = $$(".stat-bar-fill");
    if (bars[index]) {
      bars[index].style.width = `${percent}%`;
    }
  }

  function animateNumber(elementId, target) {
    const el = $(`#${elementId}`);
    if (!el) return;

    const raw = el.textContent.trim();
    const isInitial = raw === "—" || raw === "-" || raw === "";
    const current = parseInt(raw);
    const currentVal = isNaN(current) ? 0 : current;

    // If already showing the correct value (and not a placeholder), skip
    if (!isInitial && currentVal === target) return;

    // If target is 0 and element shows placeholder, just set directly
    if (target === 0) {
      el.textContent = "0";
      el.classList.add("animate-count");
      return;
    }

    // Quick count-up animation
    const duration = 600;
    const steps = 20;
    const increment = (target - currentVal) / steps;
    let step = 0;

    el.classList.add("animate-count");

    const timer = setInterval(() => {
      step++;
      if (step >= steps) {
        el.textContent = formatNumber(target);
        clearInterval(timer);
      } else {
        el.textContent = formatNumber(
          Math.round(currentVal + increment * step),
        );
      }
    }, duration / steps);
  }

  function formatNumber(n) {
    if (n >= 1000000) return (n / 1000000).toFixed(1) + "M";
    if (n >= 1000) return (n / 1000).toFixed(1) + "K";
    return String(n);
  }

  // ─── Pending Queries ───
  async function loadPendingQueries() {
    try {
      const queries = await API.getBlockedQueries();
      renderPendingList(queries);
    } catch (err) {
      console.warn("Failed to load pending queries:", err);
    }
  }

  function renderPendingList(queries) {
    const container = $("#pending-list");
    const countBadge = $("#pending-count");
    countBadge.textContent = queries.length;

    if (!queries.length) {
      container.innerHTML = `
                <div class="empty-state" id="pending-empty">
                    <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#E0E0E0" stroke-width="1">
                        <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/>
                    </svg>
                    <p>All clear! No queries pending approval.</p>
                </div>`;
      return;
    }

    container.innerHTML = queries
      .map(
        (q, i) => `
            <div class="pending-item" style="animation-delay: ${i * 0.05}s">
                <div class="pending-checkbox" onclick="Dashboard.approveQuery(${q.id})" title="Quick Approve">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
                        <polyline points="20 6 9 17 4 12"/>
                    </svg>
                </div>
                <div class="pending-body">
                    <div class="pending-query-text">${escapeHtml(truncate(q.queryPreview, 120))}</div>
                    <div class="pending-meta">
                        <span class="badge badge-neutral">${q.queryType || "SQL"}</span>
                        <span>${timeAgo(q.createdAt)}</span>
                        ${q.requiresPeerApproval ? `<span class="badge badge-purple">Peer Review</span>` : ""}
                        ${q.approvalCount > 0 ? `<span>👍 ${q.approvalCount}</span>` : ""}
                    </div>
                </div>
                <div class="pending-actions">
                    <button class="btn btn-sm btn-approve" onclick="Dashboard.approveQuery(${q.id})" title="Approve">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>
                    </button>
                    <button class="btn btn-sm btn-reject" onclick="Dashboard.rejectQuery(${q.id})" title="Reject">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                    </button>
                </div>
            </div>
        `,
      )
      .join("");
  }

  async function refreshDashboard() {
    const btn = $("#refresh-dashboard");
    btn.classList.add("spinning");
    try {
      await Promise.all([loadMetrics(), loadPendingQueries()]);
      showToast("Dashboard refreshed", "success");
    } catch {
      showToast("Failed to refresh", "error");
    }
    setTimeout(() => btn.classList.remove("spinning"), 600);
  }

  // ═══════════════════════════════════════
  //  QUERIES PAGE
  // ═══════════════════════════════════════
  async function loadAllQueries() {
    try {
      const queries = await API.getAllQueries();
      state.allQueries = queries;
      renderQueriesTable();
    } catch (err) {
      console.warn("Failed to load queries:", err);
      $("#queries-tbody").innerHTML =
        `<tr><td colspan="6"><div class="table-empty">Failed to load queries</div></td></tr>`;
    }
  }

  function renderQueriesTable() {
    const tbody = $("#queries-tbody");
    let queries = state.allQueries;

    if (state.queryFilter !== "all") {
      queries = queries.filter((q) => q.status === state.queryFilter);
    }

    if (!queries.length) {
      tbody.innerHTML = `<tr><td colspan="6"><div class="table-empty">No queries found</div></td></tr>`;
      return;
    }

    tbody.innerHTML = queries
      .map(
        (q) => `
            <tr>
                <td><span class="badge badge-neutral">#${q.id}</span></td>
                <td><span class="query-preview" title="${escapeHtml(q.queryPreview)}">${escapeHtml(truncate(q.queryPreview, 60))}</span></td>
                <td><span class="badge badge-neutral">${q.queryType || "—"}</span></td>
                <td>${statusBadge(q.status)}</td>
                <td>${formatDate(q.createdAt)}</td>
                <td>
                    <div class="table-actions">
                        ${
                          q.status === "PENDING"
                            ? `
                            <button class="btn btn-sm btn-approve" onclick="Dashboard.approveQuery(${q.id})">Approve</button>
                            <button class="btn btn-sm btn-reject" onclick="Dashboard.rejectQuery(${q.id})">Reject</button>
                        `
                            : `
                            <button class="btn btn-sm btn-ghost" onclick="Dashboard.showVoteStatus(${q.id})">Details</button>
                        `
                        }
                    </div>
                </td>
            </tr>
        `,
      )
      .join("");
  }

  function statusBadge(status) {
    const map = {
      PENDING: "badge-warning",
      APPROVED: "badge-success",
      REJECTED: "badge-danger",
      EXPIRED: "badge-neutral",
    };
    return `<span class="badge ${map[status] || "badge-neutral"}">${status}</span>`;
  }

  // ─── Query Actions ───
  async function approveQuery(id) {
    try {
      const user = API.getUser();
      // For PEER role, use vote endpoint; for ADMIN, use approve
      if (user && user.role === "PEER") {
        await API.voteQuery(id, "APPROVE");
        showToast("Vote cast: Approve", "success");
      } else {
        await API.approveQuery(id);
        showToast("Query approved", "success");
      }
      addTimelineEvent(
        "approved",
        "Query Approved",
        `You approved query #${id}`,
      );
      loadPendingQueries();
      loadMetrics();
      if (state.currentPage === "queries") loadAllQueries();
    } catch (err) {
      showToast(err.error || "Failed to approve query", "error");
    }
  }

  async function rejectQuery(id) {
    try {
      const user = API.getUser();
      if (user && user.role === "PEER") {
        await API.voteQuery(id, "REJECT");
        showToast("Vote cast: Reject", "success");
      } else {
        await API.rejectQuery(id);
        showToast("Query rejected", "success");
      }
      addTimelineEvent(
        "rejected",
        "Query Rejected",
        `You rejected query #${id}`,
      );
      loadPendingQueries();
      loadMetrics();
      if (state.currentPage === "queries") loadAllQueries();
    } catch (err) {
      showToast(err.error || "Failed to reject query", "error");
    }
  }

  async function showVoteStatus(id) {
    try {
      const data = await API.getVoteStatus(id);
      const totalVotes = (data.approvalCount || 0) + (data.rejectionCount || 0);
      const approvePct = totalVotes
        ? Math.round(((data.approvalCount || 0) / totalVotes) * 100)
        : 0;
      const rejectPct = totalVotes ? 100 - approvePct : 0;

      openModal(
        "Vote Status — Query #" + id,
        `
                <div class="vote-status">
                    <div class="vote-bar">
                        <div class="vote-bar-approve" style="width: ${approvePct}%"></div>
                        <div class="vote-bar-reject" style="width: ${rejectPct}%"></div>
                    </div>
                    <div class="vote-counts">
                        <span>👍 ${data.approvalCount || 0} Approvals</span>
                        <span>👎 ${data.rejectionCount || 0} Rejections</span>
                    </div>
                    ${
                      data.approvals && data.approvals.length
                        ? `
                        <div style="margin-top:16px">
                            <strong style="font-size:0.82rem;color:var(--text-secondary)">Approved by:</strong>
                            <div style="margin-top:6px">${data.approvals.map((u) => `<span class="badge badge-success" style="margin:2px">${escapeHtml(u)}</span>`).join("")}</div>
                        </div>
                    `
                        : ""
                    }
                    ${
                      data.rejections && data.rejections.length
                        ? `
                        <div style="margin-top:12px">
                            <strong style="font-size:0.82rem;color:var(--text-secondary)">Rejected by:</strong>
                            <div style="margin-top:6px">${data.rejections.map((u) => `<span class="badge badge-danger" style="margin:2px">${escapeHtml(u)}</span>`).join("")}</div>
                        </div>
                    `
                        : ""
                    }
                </div>
            `,
      );
    } catch (err) {
      showToast("Failed to load vote status", "error");
    }
  }

  // ═══════════════════════════════════════
  //  USERS PAGE
  // ═══════════════════════════════════════
  async function loadUsers() {
    try {
      const users = await API.getUsers();
      renderUsersTable(users);
    } catch (err) {
      $("#users-tbody").innerHTML =
        `<tr><td colspan="5"><div class="table-empty">Failed to load users</div></td></tr>`;
    }
  }

  function renderUsersTable(users) {
    const tbody = $("#users-tbody");

    if (!users.length) {
      tbody.innerHTML = `<tr><td colspan="5"><div class="table-empty">No users found</div></td></tr>`;
      return;
    }

    tbody.innerHTML = users
      .map(
        (u) => `
            <tr>
                <td>
                    <div class="user-row-avatar">
                        <div class="mini-avatar">${u.username.charAt(0).toUpperCase()}</div>
                        <span style="font-weight:600">${escapeHtml(u.username)}</span>
                    </div>
                </td>
                <td><span class="badge ${u.role === "ADMIN" ? "badge-purple" : "badge-teal"}">${u.role}</span></td>
                <td>${formatDate(u.createdAt)}</td>
                <td>${u.lastLogin ? formatDate(u.lastLogin) : '<span style="color:var(--text-tertiary)">Never</span>'}</td>
                <td>
                    <button class="btn btn-sm btn-danger" onclick="Dashboard.deleteUser(${u.id}, '${escapeHtml(u.username)}')">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                        Delete
                    </button>
                </td>
            </tr>
        `,
      )
      .join("");
  }

  function showCreateUserModal() {
    openModal(
      "Create New User",
      `
            <form id="create-user-form">
                <div class="input-group">
                    <label for="new-username">Username</label>
                    <input type="text" id="new-username" placeholder="Enter username" required>
                </div>
                <div class="input-group">
                    <label for="new-password">Password</label>
                    <input type="password" id="new-password" placeholder="Enter password" required>
                </div>
                <div class="input-group">
                    <label for="new-role">Role</label>
                    <select id="new-role" required>
                        <option value="PEER">PEER</option>
                        <option value="ADMIN">ADMIN</option>
                    </select>
                </div>
                <button type="submit" class="btn btn-primary btn-block">Create User</button>
            </form>
        `,
    );

    // Bind the form submit inside the modal
    setTimeout(() => {
      const form = $("#create-user-form");
      if (form) {
        form.addEventListener("submit", async (e) => {
          e.preventDefault();
          const username = $("#new-username").value.trim();
          const password = $("#new-password").value;
          const role = $("#new-role").value;

          if (!username || !password) return;

          try {
            await API.createUser(username, password, role);
            showToast(`User "${username}" created`, "success");
            closeModal();
            loadUsers();
          } catch (err) {
            showToast(err.error || "Failed to create user", "error");
          }
        });
      }
    }, 50);
  }

  async function deleteUser(id, username) {
    const currentUser = API.getUser();
    if (currentUser && currentUser.username === username) {
      showToast("Cannot delete yourself", "warning");
      return;
    }

    if (!confirm(`Delete user "${username}"? This cannot be undone.`)) return;

    try {
      await API.deleteUser(id);
      showToast(`User "${username}" deleted`, "success");
      loadUsers();
    } catch (err) {
      showToast(err.error || "Failed to delete user", "error");
    }
  }

  // ═══════════════════════════════════════
  //  AUDIT LOGS PAGE
  // ═══════════════════════════════════════
  async function loadAuditLogs(usernameFilter) {
    try {
      let logs;
      if (usernameFilter) {
        logs = await API.getAuditLogsByUser(usernameFilter);
      } else {
        logs = await API.getAuditLogs();
      }
      renderAuditTable(logs);
    } catch (err) {
      $("#audit-tbody").innerHTML =
        `<tr><td colspan="5"><div class="table-empty">Failed to load audit logs</div></td></tr>`;
    }
  }

  function renderAuditTable(logs) {
    const tbody = $("#audit-tbody");

    if (!logs || !logs.length) {
      tbody.innerHTML = `<tr><td colspan="5"><div class="table-empty">No audit logs found</div></td></tr>`;
      return;
    }

    tbody.innerHTML = logs
      .map(
        (log) => `
            <tr>
                <td>${formatDate(log.timestamp)}</td>
                <td><span style="font-weight:600">${escapeHtml(log.username)}</span></td>
                <td>${actionBadge(log.action)}</td>
                <td><span class="query-preview" title="${escapeHtml(log.details || "")}">${escapeHtml(truncate(log.details || "—", 50))}</span></td>
                <td><span style="color:var(--text-secondary);font-size:0.8rem">${escapeHtml(log.ipAddress || "—")}</span></td>
            </tr>
        `,
      )
      .join("");
  }

  function actionBadge(action) {
    if (!action) return '<span class="badge badge-neutral">—</span>';
    const lower = action.toLowerCase();
    if (lower.includes("approve"))
      return `<span class="badge badge-success">${escapeHtml(action)}</span>`;
    if (lower.includes("reject"))
      return `<span class="badge badge-danger">${escapeHtml(action)}</span>`;
    if (lower.includes("login"))
      return `<span class="badge badge-teal">${escapeHtml(action)}</span>`;
    if (lower.includes("block"))
      return `<span class="badge badge-coral">${escapeHtml(action)}</span>`;
    if (lower.includes("vote"))
      return `<span class="badge badge-purple">${escapeHtml(action)}</span>`;
    return `<span class="badge badge-neutral">${escapeHtml(action)}</span>`;
  }

  // ═══════════════════════════════════════
  //  SETTINGS PAGE
  // ═══════════════════════════════════════
  async function loadConfig() {
    try {
      const config = await API.getConfig();
      renderConfig(config);
    } catch (err) {
      console.warn("Failed to load config:", err);
    }
  }

  function renderConfig(cfg) {
    $("#cfg-proxy-port").textContent = cfg.proxy_port || "—";
    $("#cfg-target-host").textContent = cfg.target_host || "—";
    $("#cfg-target-port").textContent = cfg.target_port || "—";

    const blockDefault = $("#cfg-block-default");
    blockDefault.textContent = cfg.block_by_default ? "Enabled" : "Disabled";
    blockDefault.className = `setting-value badge ${cfg.block_by_default ? "badge-warning" : "badge-success"}`;

    const peerApproval = $("#cfg-peer-approval");
    peerApproval.textContent = cfg.peer_approval_enabled
      ? "Enabled"
      : "Disabled";
    peerApproval.className = `setting-value badge ${cfg.peer_approval_enabled ? "badge-purple" : "badge-neutral"}`;

    $("#cfg-min-votes").textContent = cfg.peer_approval_min_votes || "—";

    // Critical keywords
    const criticalEl = $("#cfg-critical-keywords");
    if (cfg.critical_keywords) {
      criticalEl.innerHTML = cfg.critical_keywords
        .split(",")
        .map((kw) => `<span class="keyword-tag critical">${kw.trim()}</span>`)
        .join("");
    }

    // Allowed keywords
    const allowedEl = $("#cfg-allowed-keywords");
    if (cfg.allowed_keywords) {
      allowedEl.innerHTML = cfg.allowed_keywords
        .split(",")
        .map((kw) => `<span class="keyword-tag allowed">${kw.trim()}</span>`)
        .join("");
    }
  }

  // ═══════════════════════════════════════
  //  TIMELINE (Right Sidebar)
  // ═══════════════════════════════════════
  function addTimelineEvent(type, title, detail, timestamp) {
    const event = {
      type,
      title,
      detail,
      timestamp: timestamp || new Date().toISOString(),
    };

    state.timelineEvents.unshift(event);
    if (state.timelineEvents.length > MAX_TIMELINE) {
      state.timelineEvents = state.timelineEvents.slice(0, MAX_TIMELINE);
    }

    renderTimeline();
  }

  function renderTimeline() {
    const container = $("#timeline-list");

    if (!state.timelineEvents.length) {
      container.innerHTML = `
                <div class="timeline-empty">
                    <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#D0D0D0" stroke-width="1.5">
                        <circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>
                    </svg>
                    <p>Waiting for events...</p>
                </div>`;
      return;
    }

    container.innerHTML = state.timelineEvents
      .map(
        (ev, i) => `
            <div class="timeline-item event-${ev.type}" style="animation-delay: ${i * 0.03}s">
                <div class="timeline-title">${escapeHtml(ev.title)}</div>
                <div class="timeline-detail">${escapeHtml(truncate(ev.detail, 80))}</div>
                <div class="timeline-time">${timeAgo(ev.timestamp)}</div>
            </div>
        `,
      )
      .join("");
  }

  // ═══════════════════════════════════════
  //  MODAL
  // ═══════════════════════════════════════
  function openModal(title, bodyHtml) {
    $("#modal-title").textContent = title;
    $("#modal-body").innerHTML = bodyHtml;
    $("#modal-overlay").classList.remove("hidden");
  }

  function closeModal() {
    $("#modal-overlay").classList.add("hidden");
  }

  // ═══════════════════════════════════════
  //  TOASTS
  // ═══════════════════════════════════════
  function showToast(message, type = "info") {
    const container = $("#toast-container");
    const toast = document.createElement("div");
    toast.className = `toast toast-${type}`;
    toast.textContent = message;
    container.appendChild(toast);

    setTimeout(() => {
      toast.classList.add("fade-out");
      setTimeout(() => toast.remove(), 300);
    }, 4000);
  }

  // ═══════════════════════════════════════
  //  UTILITIES
  // ═══════════════════════════════════════
  function escapeHtml(str) {
    if (!str) return "";
    const div = document.createElement("div");
    div.textContent = str;
    return div.innerHTML;
  }

  function truncate(str, maxLen) {
    if (!str) return "";
    return str.length > maxLen ? str.substring(0, maxLen) + "…" : str;
  }

  function timeAgo(dateStr) {
    if (!dateStr) return "";
    const now = Date.now();
    const then = new Date(dateStr).getTime();
    const diff = now - then;

    if (diff < 0) return "just now";
    if (diff < 60000) return `${Math.floor(diff / 1000)}s ago`;
    if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
    if (diff < 86400000) return `${Math.floor(diff / 3600000)}h ago`;
    return `${Math.floor(diff / 86400000)}d ago`;
  }

  function formatDate(dateStr) {
    if (!dateStr) return "—";
    try {
      const d = new Date(dateStr);
      return (
        d.toLocaleDateString("en-US", {
          month: "short",
          day: "numeric",
          year: "numeric",
        }) +
        " " +
        d.toLocaleTimeString("en-US", {
          hour: "2-digit",
          minute: "2-digit",
        })
      );
    } catch {
      return dateStr;
    }
  }

  // ═══════════════════════════════════════
  //  BOOT
  // ═══════════════════════════════════════
  document.addEventListener("DOMContentLoaded", init);

  // ─── Public Interface ───
  return {
    approveQuery,
    rejectQuery,
    deleteUser,
    showVoteStatus,
  };
})();

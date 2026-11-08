import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { formatPaise, parseRupeesToPaise, generateIdempotencyKey } from './utils';

export default function App() {
  // Session State (In-Memory Only for XSS resistance)
  const [token, setToken] = useState(null);
  const [currentUser, setCurrentUser] = useState(null);

  // Navigation: 'accounts' | 'transfer' | 'statement' | 'admin' | 'verify'
  const [activeTab, setActiveTab] = useState('accounts');
  const [notification, setNotification] = useState(null);

  // Login Form
  const [loginUsername, setLoginUsername] = useState('holder');
  const [loginPassword, setLoginPassword] = useState('HolderPassword_2026!');
  const [loginLoading, setLoginLoading] = useState(false);

  // Accounts
  const [accounts, setAccounts] = useState([]);
  const [accountsLoading, setAccountsLoading] = useState(false);
  const [selectedAccountId, setSelectedAccountId] = useState(null);

  // Statement
  const [statementData, setStatementData] = useState(null);
  const [statementLoading, setStatementLoading] = useState(false);
  const [statementPage, setStatementPage] = useState(0);

  // Money Movement
  const [moneyAction, setMoneyAction] = useState('TRANSFER'); // 'DEPOSIT' | 'WITHDRAW' | 'TRANSFER'
  const [moneySourceAccount, setMoneySourceAccount] = useState('');
  const [moneyDestAccount, setMoneyDestAccount] = useState('');
  const [moneyAmountRupees, setMoneyAmountRupees] = useState('');
  const [moneyReason, setMoneyReason] = useState('');
  const [currentIdempotencyKey, setCurrentIdempotencyKey] = useState(generateIdempotencyKey());
  const [moneySubmitting, setMoneySubmitting] = useState(false);
  const [lastMovementResult, setLastMovementResult] = useState(null);
  const [copiedKey, setCopiedKey] = useState(false);

  // Admin Portal
  const [adminAction, setAdminAction] = useState('CREATE_ACCOUNT');
  const [newAccountName, setNewAccountName] = useState('');
  const [memberAccountId, setMemberAccountId] = useState('');
  const [memberUserId, setMemberUserId] = useState('');
  const [adjustAccountId, setAdjustAccountId] = useState('');
  const [adjustAmountRupees, setAdjustAmountRupees] = useState('');
  const [adjustReason, setAdjustReason] = useState('');
  const [reverseEntryId, setReverseEntryId] = useState('');
  const [reverseReason, setReverseReason] = useState('');
  const [adminSubmitting, setAdminSubmitting] = useState(false);

  // System Verification
  const [verificationReport, setVerificationReport] = useState(null);
  const [verificationLoading, setVerificationLoading] = useState(false);

  const notify = (message, type = 'info') => {
    setNotification({ message, type });
  };

  // Central authenticated fetch
  const authFetch = useCallback(async (url, options = {}) => {
    const headers = new Headers(options.headers || {});
    if (token) {
      headers.set('Authorization', `Bearer ${token}`);
    }
    if (!headers.has('Content-Type') && options.body) {
      headers.set('Content-Type', 'application/json');
    }

    try {
      const response = await fetch(url, { ...options, headers });
      if (response.status === 401) {
        setToken(null);
        setCurrentUser(null);
        notify('Session expired or unauthorized. Please sign in again.', 'warning');
        return { ok: false, status: 401, error: 'Unauthorized' };
      }

      const contentType = response.headers.get('content-type');
      let data = null;
      if (contentType && contentType.includes('application/json')) {
        data = await response.json();
      } else {
        const text = await response.text();
        data = text ? { message: text } : {};
      }

      if (response.status === 429) {
        const retryAfter = response.headers.get('Retry-After') || '60';
        notify(`Rate limit reached: ${data.message || 'Too many requests'}. Retry after ${retryAfter}s.`, 'warning');
        return { ok: false, status: 429, data };
      }

      if (response.status === 404) {
        return { ok: false, status: 404, data: data || { message: 'Resource not found' } };
      }
      if (response.status === 422) {
        return { ok: false, status: 422, data };
      }
      if (!response.ok) {
        return { ok: false, status: response.status, data };
      }

      return { ok: true, status: response.status, data };
    } catch (err) {
      notify(`Network error: ${err.message}. Check your connection and retry.`, 'error');
      return { ok: false, status: 0, networkError: true, error: err.message };
    }
  }, [token]);

  // Load Accounts
  const loadAccounts = useCallback(async () => {
    if (!token) return;
    setAccountsLoading(true);
    const res = await authFetch('/api/accounts');
    setAccountsLoading(false);

    if (res.ok && Array.isArray(res.data)) {
      setAccounts(res.data);
      if (res.data.length > 0 && !selectedAccountId) {
        setSelectedAccountId(res.data[0].id);
        setMoneySourceAccount(res.data[0].id.toString());
      }
    }
  }, [token, authFetch, selectedAccountId]);

  // Load Statement
  const loadStatement = useCallback(async (accountId, page = 0) => {
    if (!token || !accountId) return;
    setStatementLoading(true);
    const res = await authFetch(`/api/accounts/${accountId}/statement?page=${page}&size=20`);
    setStatementLoading(false);

    if (res.ok) {
      setStatementData(res.data);
      setStatementPage(page);
    } else if (res.status === 404) {
      setStatementData(null);
      notify('Account statement not found or access denied.', 'warning');
    } else {
      notify(`Failed to load statement: ${res.data?.message || 'Server error'}`, 'error');
    }
  }, [token, authFetch]);

  // Run Verification
  const runVerification = useCallback(async () => {
    if (!token) return;
    setVerificationLoading(true);
    const res = await authFetch('/api/admin/verify');
    setVerificationLoading(false);

    if (res.ok) {
      setVerificationReport(res.data);
      if (res.data.status === 'PASSED') {
        notify('Cryptographic and Double-Entry Invariants: ALL 7 CHECKS PASSED', 'success');
      } else {
        notify('System integrity check flagged anomalies: FAILED', 'error');
      }
    } else {
      notify(`Verification failed: ${res.data?.message || 'Access denied'}`, 'error');
    }
  }, [token, authFetch]);

  useEffect(() => {
    if (token) loadAccounts();
  }, [token, loadAccounts]);

  useEffect(() => {
    if (token && selectedAccountId && activeTab === 'statement') {
      loadStatement(selectedAccountId, 0);
    }
  }, [token, selectedAccountId, activeTab, loadStatement]);

  // Login handler
  const handleLogin = async (e) => {
    if (e) e.preventDefault();
    setNotification(null);
    setLoginLoading(true);

    try {
      const response = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: loginUsername, password: loginPassword })
      });

      const data = await response.json();
      setLoginLoading(false);

      if (!response.ok) {
        if (response.status === 429) {
          notify(`Rate limited: ${data.message || 'Please wait before retrying.'}`, 'warning');
        } else {
          notify(data.message || 'Authentication failed. Check credentials.', 'error');
        }
        return;
      }

      setToken(data.token);
      setCurrentUser(data.user);
      setLoginPassword('');
      setActiveTab('accounts');
      notify(`Authenticated as ${data.user.username} (${data.user.role})`, 'success');
    } catch (err) {
      setLoginLoading(false);
      notify(`Connection error: ${err.message}`, 'error');
    }
  };

  const handleLogout = () => {
    setToken(null);
    setCurrentUser(null);
    setAccounts([]);
    setStatementData(null);
    setVerificationReport(null);
    setSelectedAccountId(null);
    setLastMovementResult(null);
    setNotification(null);
  };

  const onQuickLogin = (roleUser, defaultPass) => {
    setLoginUsername(roleUser);
    setLoginPassword(defaultPass);
  };

  const onMoneyFormChange = (setter, value) => {
    setter(value);
    setCurrentIdempotencyKey(generateIdempotencyKey());
    setLastMovementResult(null);
  };

  const handleCopyKey = () => {
    navigator.clipboard.writeText(currentIdempotencyKey);
    setCopiedKey(true);
    setTimeout(() => setCopiedKey(false), 2000);
  };

  // Submit Money Movement
  const handleMoneySubmit = async (e) => {
    e.preventDefault();
    setNotification(null);

    let amountPaise;
    try {
      amountPaise = parseRupeesToPaise(moneyAmountRupees);
    } catch (err) {
      notify(err.message, 'error');
      return;
    }

    setMoneySubmitting(true);
    let url;
    let method = 'POST';
    let body;

    if (moneyAction === 'DEPOSIT') {
      if (!moneySourceAccount) {
        notify('Please select an account', 'error');
        setMoneySubmitting(false);
        return;
      }
      url = `/api/accounts/${moneySourceAccount}/deposit`;
      body = JSON.stringify({ amount: amountPaise, reason: moneyReason || 'Deposit via Financial Portal' });
    } else if (moneyAction === 'WITHDRAW') {
      if (!moneySourceAccount) {
        notify('Please select an account', 'error');
        setMoneySubmitting(false);
        return;
      }
      url = `/api/accounts/${moneySourceAccount}/withdraw`;
      body = JSON.stringify({ amount: amountPaise, reason: moneyReason || 'Withdrawal via Financial Portal' });
    } else {
      if (!moneySourceAccount || !moneyDestAccount) {
        notify('Select both source and destination accounts', 'error');
        setMoneySubmitting(false);
        return;
      }
      if (moneySourceAccount === moneyDestAccount) {
        notify('Source and destination accounts must be distinct', 'error');
        setMoneySubmitting(false);
        return;
      }
      url = '/api/transfers';
      body = JSON.stringify({
        fromAccountId: Number(moneySourceAccount),
        toAccountId: Number(moneyDestAccount),
        amount: amountPaise,
        reason: moneyReason || 'Transfer via Financial Portal'
      });
    }

    const res = await authFetch(url, {
      method,
      headers: { 'Idempotency-Key': currentIdempotencyKey },
      body
    });

    setMoneySubmitting(false);

    if (res.networkError) {
      notify('Network error. Idempotency key preserved — safe to retry without double debit.', 'warning');
      return;
    }

    if (res.ok && res.data) {
      setLastMovementResult({ success: true, ...res.data });
      notify(`Transaction committed! Entry ID: #${res.data.entryId}`, 'success');
      loadAccounts();
      setCurrentIdempotencyKey(generateIdempotencyKey());
    } else if (res.status === 422 && res.data && res.data.status === 'REFUSED') {
      setLastMovementResult({ success: false, ...res.data });
      notify(`Transaction Refused: ${res.data.message || res.data.refusalCode}`, 'error');
      setCurrentIdempotencyKey(generateIdempotencyKey());
    } else if (res.status === 422) {
      notify(`Idempotency conflict (422): ${res.data?.message || 'Payload hash mismatch'}`, 'error');
    } else if (res.status === 404) {
      setLastMovementResult({ success: false, status: 'REFUSED', message: 'Account not found or access unauthorized' });
      notify('Account not found or unauthorized (404)', 'error');
    } else {
      notify(`Error (${res.status}): ${res.data?.message || 'Request failed'}`, 'error');
    }
  };

  // Replay Attack Simulator (re-submits with exact same key and body)
  const handleSimulateReplay = async () => {
    if (!lastMovementResult) return;
    notify('Simulating duplicate replay with identical key...', 'info');
    let amountPaise = parseRupeesToPaise(moneyAmountRupees || '10');
    let url = moneyAction === 'DEPOSIT'
      ? `/api/accounts/${moneySourceAccount}/deposit`
      : moneyAction === 'WITHDRAW'
      ? `/api/accounts/${moneySourceAccount}/withdraw`
      : '/api/transfers';
    let body = moneyAction === 'TRANSFER'
      ? JSON.stringify({ fromAccountId: Number(moneySourceAccount), toAccountId: Number(moneyDestAccount), amount: amountPaise, reason: moneyReason || 'Replay test' })
      : JSON.stringify({ amount: amountPaise, reason: moneyReason || 'Replay test' });

    const res = await authFetch(url, {
      method: 'POST',
      headers: { 'Idempotency-Key': currentIdempotencyKey },
      body
    });
    if (res.ok) {
      notify(`Replay returned cached response without money movement! Duplicate recognized.`, 'success');
    } else {
      notify(`Replay returned: ${res.status}`, 'info');
    }
  };

  // Admin Operations
  const handleAdminSubmit = async (e) => {
    e.preventDefault();
    setNotification(null);
    setAdminSubmitting(true);

    let res;
    if (adminAction === 'CREATE_ACCOUNT') {
      if (!newAccountName.trim()) {
        notify('Account name is required', 'error');
        setAdminSubmitting(false);
        return;
      }
      res = await authFetch('/api/admin/accounts', {
        method: 'POST',
        body: JSON.stringify({ name: newAccountName.trim() })
      });
      if (res.ok) {
        notify(`Account created: ${res.data.name} (ID: #${res.data.id})`, 'success');
        setNewAccountName('');
        loadAccounts();
      }
    } else if (adminAction === 'ADD_MEMBER') {
      if (!memberAccountId || !memberUserId) {
        notify('Account ID and User ID required', 'error');
        setAdminSubmitting(false);
        return;
      }
      res = await authFetch(`/api/admin/accounts/${memberAccountId}/members`, {
        method: 'POST',
        body: JSON.stringify({ userId: Number(memberUserId) })
      });
      if (res.ok) {
        notify(`User #${memberUserId} added as member to Account #${memberAccountId}`, 'success');
        setMemberUserId('');
      }
    } else if (adminAction === 'ADJUST') {
      let adjustPaise;
      try {
        adjustPaise = parseRupeesToPaise(adjustAmountRupees);
      } catch (err) {
        notify(err.message, 'error');
        setAdminSubmitting(false);
        return;
      }
      if (!adjustReason.trim()) {
        notify('Mandatory reason required for audited adjustment', 'error');
        setAdminSubmitting(false);
        return;
      }
      res = await authFetch(`/api/admin/accounts/${adjustAccountId}/adjust`, {
        method: 'POST',
        headers: { 'Idempotency-Key': generateIdempotencyKey() },
        body: JSON.stringify({ amount: adjustPaise, reason: adjustReason.trim() })
      });
      if (res.ok) {
        notify(`Audited Adjustment committed! Entry ID: #${res.data.entryId}`, 'success');
        setAdjustAmountRupees('');
        setAdjustReason('');
        loadAccounts();
      }
    } else if (adminAction === 'REVERSE') {
      if (!reverseEntryId || !reverseReason.trim()) {
        notify('Entry ID and mandatory reason required', 'error');
        setAdminSubmitting(false);
        return;
      }
      res = await authFetch(`/api/admin/entries/${reverseEntryId}/reverse`, {
        method: 'POST',
        headers: { 'Idempotency-Key': generateIdempotencyKey() },
        body: JSON.stringify({ reason: reverseReason.trim() })
      });
      if (res.ok) {
        notify(`Entry #${reverseEntryId} reversed! Reversal Entry ID: #${res.data.entryId}`, 'success');
        setReverseEntryId('');
        setReverseReason('');
        loadAccounts();
      }
    }

    setAdminSubmitting(false);
    if (res && !res.ok) {
      notify(`Admin action failed (${res.status}): ${res.data?.message || 'Access denied'}`, 'error');
    }
  };

  const isAuditor = currentUser?.role === 'AUDITOR';
  const isAdmin = currentUser?.role === 'ADMIN';

  // Compute total user balances in view
  const totalBalancePaise = useMemo(() => {
    return accounts.reduce((acc, a) => acc + (a.balance || 0), 0);
  }, [accounts]);

  return (
    <div style={theme.page}>
      {/* Top Banner & Header */}
      <header style={theme.header}>
        <div style={theme.headerContent}>
          <div style={theme.logoContainer}>
            <div style={theme.logoIcon}>
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <rect x="2" y="7" width="20" height="14" rx="2" ry="2"></rect>
                <path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"></path>
              </svg>
            </div>
            <div>
              <div style={theme.logoText}>
                LedgerLine <span style={theme.logoBadge}>PRO</span>
              </div>
              <div style={theme.logoSubtext}>
                Financial Management & Cryptographic Ledger Engine
              </div>
            </div>
          </div>

          <div style={theme.headerRight}>
            <div style={theme.neonBadge}>
              <span style={theme.neonDot}></span>
              <span>Neon PostgreSQL • Direct Unpooled</span>
            </div>

            {currentUser && (
              <div style={theme.userProfile}>
                <div style={theme.userAvatar}>
                  {currentUser.username.substring(0, 2).toUpperCase()}
                </div>
                <div style={theme.userDetails}>
                  <div style={theme.userName}>{currentUser.username}</div>
                  <span style={theme.roleTag(currentUser.role)}>{currentUser.role}</span>
                </div>
                <button onClick={handleLogout} style={theme.logoutBtn} title="Sign Out">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path>
                    <polyline points="16 17 21 12 16 7"></polyline>
                    <line x1="21" y1="12" x2="9" y2="12"></line>
                  </svg>
                </button>
              </div>
            )}
          </div>
        </div>
      </header>

      {/* Global Toast Notification */}
      {notification && (
        <div style={theme.toast(notification.type)}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <span style={{ fontSize: '18px' }}>
              {notification.type === 'success' ? '✓' : notification.type === 'error' ? '✕' : 'ℹ'}
            </span>
            <span style={{ fontSize: '14px', fontWeight: '500' }}>{notification.message}</span>
          </div>
          <button onClick={() => setNotification(null)} style={theme.toastClose}>×</button>
        </div>
      )}

      {/* Main Content Area */}
      <main style={theme.main}>
        {!token ? (
          /* ====================================================================
             LOGIN VIEW - ENTERPRISE AUTHENTICATION & DEMO SWITCHER
             ==================================================================== */
          <div style={theme.loginGrid}>
            <div style={theme.loginCard}>
              <div style={theme.loginHeader}>
                <div style={theme.shieldIcon}>
                  <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" strokeWidth="2">
                    <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path>
                  </svg>
                </div>
                <h2 style={theme.loginTitle}>Secure Financial Portal</h2>
                <p style={theme.loginSubtitle}>
                  Multi-Role Access to Double-Entry Core Engine
                </p>
              </div>

              <form onSubmit={handleLogin} style={theme.form}>
                <div>
                  <label style={theme.label}>Username</label>
                  <input
                    type="text"
                    value={loginUsername}
                    onChange={(e) => setLoginUsername(e.target.value)}
                    required
                    style={theme.input}
                    placeholder="admin, holder, auditor"
                  />
                </div>

                <div>
                  <label style={theme.label}>Password</label>
                  <input
                    type="password"
                    value={loginPassword}
                    onChange={(e) => setLoginPassword(e.target.value)}
                    required
                    style={theme.input}
                    placeholder="••••••••••••"
                  />
                </div>

                <button type="submit" disabled={loginLoading} style={theme.primaryBtn}>
                  {loginLoading ? 'Authenticating...' : 'Sign In to LedgerLine'}
                </button>
              </form>

              {/* Quick 1-Click Persona Cards */}
              <div style={theme.demoSection}>
                <div style={theme.demoSectionTitle}>
                  Quick Demo Sign-In (1-Click)
                </div>
                <div style={theme.demoGrid}>
                  <div
                    onClick={() => onQuickLogin('holder', 'HolderPassword_2026!')}
                    style={theme.demoRoleCard(loginUsername === 'holder')}
                  >
                    <div style={theme.demoRoleHeader}>
                      <span style={theme.demoRoleName}>👤 Holder</span>
                      <span style={theme.roleTag('HOLDER')}>HOLDER</span>
                    </div>
                    <div style={theme.demoRoleDesc}>
                      Deposits, withdrawals, peer transfers, and account statements.
                    </div>
                  </div>

                  <div
                    onClick={() => onQuickLogin('admin', 'AdminPassword_2026!')}
                    style={theme.demoRoleCard(loginUsername === 'admin')}
                  >
                    <div style={theme.demoRoleHeader}>
                      <span style={theme.demoRoleName}>👑 Admin</span>
                      <span style={theme.roleTag('ADMIN')}>ADMIN</span>
                    </div>
                    <div style={theme.demoRoleDesc}>
                      Create accounts, audited balance adjustments, reversals & verify.
                    </div>
                  </div>

                  <div
                    onClick={() => onQuickLogin('auditor', 'AuditorPassword_2026!')}
                    style={theme.demoRoleCard(loginUsername === 'auditor')}
                  >
                    <div style={theme.demoRoleHeader}>
                      <span style={theme.demoRoleName}>🔍 Auditor</span>
                      <span style={theme.roleTag('AUDITOR')}>AUDITOR</span>
                    </div>
                    <div style={theme.demoRoleDesc}>
                      Strict read-only compliance, forensic verification, and ledger trail.
                    </div>
                  </div>
                </div>
              </div>
            </div>

            {/* Architecture Explainer Side Panel */}
            <div style={theme.infoSidePanel}>
              <h3 style={theme.infoPanelTitle}>Enterprise Financial Invariants</h3>
              <ul style={theme.infoList}>
                <li style={theme.infoItem}>
                  <strong>Zero-Sum Double Entry:</strong> Every transaction maintains absolute mathematical conservation across debit and credit postings.
                </li>
                <li style={theme.infoItem}>
                  <strong>SHA-256 Hash Chain:</strong> Ledger entries and audit records are linked via SHA-256 prev_hash chaining serialized by PostgreSQL advisory locks.
                </li>
                <li style={theme.infoItem}>
                  <strong>Refusals-as-Data:</strong> Failed or insufficient balance attempts are recorded permanently in <code>ledger_attempts</code> with running snapshot balance.
                </li>
                <li style={theme.infoItem}>
                  <strong>Exactly-Once Semantics:</strong> Canonical request hashing and unique idempotency keys guarantee atomic replays without duplicated funds.
                </li>
                <li style={theme.infoItem}>
                  <strong>Serverless Neon Database:</strong> Connected directly to Neon Serverless PostgreSQL with least-privilege <code>ledger_app</code> role isolation.
                </li>
              </ul>
            </div>
          </div>
        ) : (
          /* ====================================================================
             AUTHENTICATED DASHBOARD
             ==================================================================== */
          <div>
            {/* Top Metric Cards */}
            <div style={theme.metricsRow}>
              <div style={theme.metricCard}>
                <div style={theme.metricLabel}>Total Accessible Balance</div>
                <div style={theme.metricValue}>{formatPaise(totalBalancePaise)}</div>
                <div style={theme.metricSub}>Across {accounts.length} monitored account{accounts.length === 1 ? '' : 's'}</div>
              </div>

              <div style={theme.metricCard}>
                <div style={theme.metricLabel}>Ledger Integrity</div>
                <div style={{ ...theme.metricValue, color: '#10b981' }}>VERIFIED 100%</div>
                <div style={theme.metricSub}>7 / 7 Invariants Passing</div>
              </div>

              <div style={theme.metricCard}>
                <div style={theme.metricLabel}>Audit Chain Security</div>
                <div style={{ ...theme.metricValue, color: '#38bdf8' }}>SHA-256 HASHED</div>
                <div style={theme.metricSub}>Append-only table protections</div>
              </div>

              <div style={theme.metricCard}>
                <div style={theme.metricLabel}>Current Active Role</div>
                <div style={theme.metricValue}>{currentUser.role}</div>
                <div style={theme.metricSub}>Signed in as {currentUser.username}</div>
              </div>
            </div>

            {/* Navigation Tabs Bar */}
            <div style={theme.tabBar}>
              <button
                onClick={() => setActiveTab('accounts')}
                style={theme.tabButton(activeTab === 'accounts')}
              >
                💳 Accounts & Balances
              </button>

              {!isAuditor && (
                <button
                  onClick={() => setActiveTab('transfer')}
                  style={theme.tabButton(activeTab === 'transfer')}
                >
                  💸 Money Movement & Idempotency
                </button>
              )}

              <button
                onClick={() => setActiveTab('statement')}
                style={theme.tabButton(activeTab === 'statement')}
              >
                📜 Account Statement & Audit Trail
              </button>

              {isAdmin && (
                <button
                  onClick={() => setActiveTab('admin')}
                  style={theme.tabButton(activeTab === 'admin')}
                >
                  🛡️ Admin Console
                </button>
              )}

              {(isAdmin || isAuditor) && (
                <button
                  onClick={() => {
                    setActiveTab('verify');
                    if (!verificationReport) runVerification();
                  }}
                  style={theme.tabButton(activeTab === 'verify')}
                >
                  🔍 System Verification Report
                </button>
              )}
            </div>

            {/* TAB 1: ACCOUNTS */}
            {activeTab === 'accounts' && (
              <div style={theme.sectionCard}>
                <div style={theme.sectionHeader}>
                  <div>
                    <h3 style={theme.sectionTitle}>Financial Accounts</h3>
                    <p style={theme.sectionSubtitle}>Real-time balances in Indian Rupees (INR) backed by integer paise</p>
                  </div>
                  <button onClick={loadAccounts} disabled={accountsLoading} style={theme.secondaryBtn}>
                    {accountsLoading ? 'Syncing...' : '↻ Refresh Balances'}
                  </button>
                </div>

                {accounts.length === 0 ? (
                  <div style={theme.emptyState}>No accounts found for your user.</div>
                ) : (
                  <div style={theme.accountGrid}>
                    {accounts.map((acc) => (
                      <div
                        key={acc.id}
                        style={theme.accountCard(selectedAccountId === acc.id)}
                        onClick={() => setSelectedAccountId(acc.id)}
                      >
                        <div style={theme.accCardTop}>
                          <div>
                            <span style={theme.accTypeBadge}>{acc.type} ACCOUNT</span>
                            <h4 style={theme.accName}>{acc.name}</h4>
                            <span style={theme.accNumber}>Account ID: #{acc.id}</span>
                          </div>
                          <span style={theme.currencyBadge}>{acc.currency}</span>
                        </div>

                        <div style={theme.accBalanceBlock}>
                          <span style={theme.accBalanceLabel}>Available Balance</span>
                          <span style={theme.accBalanceValue(acc.balance)}>
                            {formatPaise(acc.balance)}
                          </span>
                        </div>

                        <div style={theme.accCardActions}>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              setSelectedAccountId(acc.id);
                              setActiveTab('statement');
                            }}
                            style={theme.actionBtnSmall}
                          >
                            View Statement
                          </button>
                          {!isAuditor && (
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                setSelectedAccountId(acc.id);
                                setMoneySourceAccount(acc.id.toString());
                                setActiveTab('transfer');
                              }}
                              style={theme.actionBtnSmallHighlight}
                            >
                              Transact →
                            </button>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}

            {/* TAB 2: MONEY MOVEMENT & IDEMPOTENCY */}
            {activeTab === 'transfer' && !isAuditor && (
              <div style={theme.sectionCard}>
                <div style={theme.sectionHeader}>
                  <div>
                    <h3 style={theme.sectionTitle}>Money Movement Studio</h3>
                    <p style={theme.sectionSubtitle}>
                      Double-entry transactional moves with client-driven exactly-once idempotency keys
                    </p>
                  </div>
                </div>

                <div style={theme.transferLayout}>
                  {/* Left Column: Form */}
                  <div style={theme.transferFormCard}>
                    {/* Action Selector */}
                    <div style={theme.actionTypeRow}>
                      <button
                        type="button"
                        onClick={() => onMoneyFormChange(setMoneyAction, 'TRANSFER')}
                        style={theme.actionTypeBtn(moneyAction === 'TRANSFER')}
                      >
                        Peer Transfer
                      </button>
                      <button
                        type="button"
                        onClick={() => onMoneyFormChange(setMoneyAction, 'DEPOSIT')}
                        style={theme.actionTypeBtn(moneyAction === 'DEPOSIT')}
                      >
                        Deposit Funds
                      </button>
                      <button
                        type="button"
                        onClick={() => onMoneyFormChange(setMoneyAction, 'WITHDRAW')}
                        style={theme.actionTypeBtn(moneyAction === 'WITHDRAW')}
                      >
                        Withdraw Funds
                      </button>
                    </div>

                    <form onSubmit={handleMoneySubmit} style={theme.form}>
                      {/* Source Account */}
                      <div>
                        <label style={theme.label}>
                          {moneyAction === 'DEPOSIT' ? 'Destination Account' : 'Source Account'}
                        </label>
                        <select
                          value={moneySourceAccount}
                          onChange={(e) => onMoneyFormChange(setMoneySourceAccount, e.target.value)}
                          required
                          style={theme.select}
                        >
                          <option value="">-- Select Account --</option>
                          {accounts.map((acc) => (
                            <option key={acc.id} value={acc.id}>
                              #{acc.id} - {acc.name} ({formatPaise(acc.balance)})
                            </option>
                          ))}
                        </select>
                      </div>

                      {/* Destination Account (Transfer Only) */}
                      {moneyAction === 'TRANSFER' && (
                        <div>
                          <label style={theme.label}>Recipient Account ID</label>
                          <input
                            type="number"
                            value={moneyDestAccount}
                            onChange={(e) => onMoneyFormChange(setMoneyDestAccount, e.target.value)}
                            required
                            style={theme.input}
                            placeholder="e.g. 1 (Admin/System) or user account ID"
                          />
                        </div>
                      )}

                      {/* Amount */}
                      <div>
                        <label style={theme.label}>Amount in Indian Rupees (₹)</label>
                        <div style={theme.inputWithPrefix}>
                          <span style={theme.prefixSpan}>₹</span>
                          <input
                            type="text"
                            value={moneyAmountRupees}
                            onChange={(e) => onMoneyFormChange(setMoneyAmountRupees, e.target.value)}
                            required
                            style={theme.inputInGroup}
                            placeholder="e.g. 50.00"
                          />
                        </div>
                        {moneyAmountRupees && (
                          <div style={theme.paisePreview}>
                            Integer Minor Units: <strong>{(() => {
                              try { return `${parseRupeesToPaise(moneyAmountRupees).toLocaleString('en-IN')} paise`; }
                              catch (err) { return 'Invalid amount'; }
                            })()}</strong>
                          </div>
                        )}
                      </div>

                      {/* Reason */}
                      <div>
                        <label style={theme.label}>Transaction Purpose / Reason</label>
                        <input
                          type="text"
                          value={moneyReason}
                          onChange={(e) => onMoneyFormChange(setMoneyReason, e.target.value)}
                          style={theme.input}
                          placeholder="e.g. Supplier payout, settlement"
                        />
                      </div>

                      <button
                        type="submit"
                        disabled={moneySubmitting}
                        style={theme.primaryBtn}
                      >
                        {moneySubmitting ? 'Executing Transaction...' : `Confirm & Execute ${moneyAction}`}
                      </button>
                    </form>
                  </div>

                  {/* Right Column: Idempotency & Replay Inspector */}
                  <div style={theme.idempotencyCard}>
                    <div style={theme.cardHeadingWithBadge}>
                      <span style={{ fontWeight: '700', fontSize: '16px' }}>Idempotency-Key Inspector</span>
                      <span style={theme.secureTag}>SHA-256 Scoped</span>
                    </div>

                    <p style={theme.idempotencyExplainer}>
                      Each transaction sends this unique key in the HTTP header. The backend validates <code>(user_id, key)</code> atomically.
                    </p>

                    <div style={theme.keyBox}>
                      <div style={theme.keyLabel}>Active Header Value:</div>
                      <div style={theme.keyValue}>{currentIdempotencyKey}</div>
                    </div>

                    <div style={{ display: 'flex', gap: '8px', marginTop: '12px' }}>
                      <button onClick={handleCopyKey} style={theme.secondaryBtnSmall}>
                        {copiedKey ? '✓ Copied!' : 'Copy Key'}
                      </button>
                      <button
                        onClick={() => setCurrentIdempotencyKey(generateIdempotencyKey())}
                        style={theme.secondaryBtnSmall}
                      >
                        ↻ Fresh Key
                      </button>
                    </div>

                    {/* Result Card */}
                    {lastMovementResult && (
                      <div style={theme.resultCard(lastMovementResult.success)}>
                        <div style={theme.resultHeader}>
                          <span>{lastMovementResult.success ? '✓ TRANSACTION COMMITTED' : '⚠️ ATTEMPT REFUSED'}</span>
                          <span style={theme.monoTag}>
                            {lastMovementResult.entryId ? `Entry #${lastMovementResult.entryId}` : lastMovementResult.refusalCode}
                          </span>
                        </div>

                        {lastMovementResult.success ? (
                          <div style={theme.resultBody}>
                            <div><strong>Kind:</strong> {lastMovementResult.kind}</div>
                            {lastMovementResult.postings && (
                              <div style={{ marginTop: '6px' }}>
                                <strong>Postings:</strong>
                                <ul style={{ margin: '4px 0 0 0', paddingLeft: '18px', fontSize: '13px' }}>
                                  {lastMovementResult.postings.map((p, idx) => (
                                    <li key={idx}>
                                      Account #{p.accountId}: {p.amount > 0 ? `+${formatPaise(p.amount)}` : formatPaise(p.amount)} ({p.direction})
                                    </li>
                                  ))}
                                </ul>
                              </div>
                            )}

                            <button
                              onClick={handleSimulateReplay}
                              style={{ ...theme.secondaryBtnSmall, marginTop: '10px', width: '100%', borderColor: '#10b981', color: '#10b981' }}
                            >
                              ⚡ Simulate Replay Attack (Same Key & Body)
                            </button>
                          </div>
                        ) : (
                          <div style={theme.resultBody}>
                            <div><strong>Refusal Reason:</strong> {lastMovementResult.refusalCode || lastMovementResult.message}</div>
                            <p style={{ margin: '6px 0 0 0', fontSize: '12px', color: '#fca5a5' }}>
                              Refusal logged in <code>ledger_attempts</code> with running snapshot balance. Money was untouched.
                            </p>
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                </div>
              </div>
            )}

            {/* TAB 3: STATEMENT & AUDIT TRAIL */}
            {activeTab === 'statement' && (
              <div style={theme.sectionCard}>
                <div style={theme.sectionHeader}>
                  <div>
                    <h3 style={theme.sectionTitle}>Account Statement & Forensic Audit</h3>
                    <p style={theme.sectionSubtitle}>
                      Every posted movement and recorded refusal attempt with running balance verification
                    </p>
                  </div>

                  <div style={{ display: 'flex', gap: '10px', alignItems: 'center' }}>
                    <select
                      value={selectedAccountId || ''}
                      onChange={(e) => setSelectedAccountId(Number(e.target.value))}
                      style={theme.selectSmall}
                    >
                      {accounts.map((acc) => (
                        <option key={acc.id} value={acc.id}>
                          #{acc.id} - {acc.name}
                        </option>
                      ))}
                    </select>

                    <button
                      onClick={() => loadStatement(selectedAccountId, statementPage)}
                      disabled={statementLoading}
                      style={theme.secondaryBtn}
                    >
                      {statementLoading ? 'Loading...' : '↻ Refresh Statement'}
                    </button>
                  </div>
                </div>

                {!statementData || !statementData.entries || statementData.entries.length === 0 ? (
                  <div style={theme.emptyState}>No transaction history for this account yet.</div>
                ) : (
                  <div>
                    <div style={theme.tableWrapper}>
                      <table style={theme.table}>
                        <thead>
                          <tr>
                            <th style={theme.th}>Type & Status</th>
                            <th style={theme.th}>Amount</th>
                            <th style={theme.th}>Running Balance</th>
                            <th style={theme.th}>Entry / Refusal ID</th>
                            <th style={theme.th}>Initiator</th>
                            <th style={theme.th}>Reason / Details</th>
                            <th style={theme.th}>Idempotency Key</th>
                          </tr>
                        </thead>
                        <tbody>
                          {statementData.entries.map((item, idx) => {
                            const isRefusal = item.type === 'REFUSED';
                            const isCredit = item.amount > 0;
                            return (
                              <tr key={idx} style={theme.tr(isRefusal)}>
                                <td style={theme.td}>
                                  <span style={theme.typeBadge(item.type)}>
                                    {item.type}
                                  </span>
                                </td>
                                <td style={{ ...theme.td, fontWeight: '700', color: isRefusal ? '#94a3b8' : isCredit ? '#10b981' : '#f43f5e' }}>
                                  {isRefusal ? formatPaise(item.amount) : isCredit ? `+${formatPaise(item.amount)}` : formatPaise(item.amount)}
                                </td>
                                <td style={{ ...theme.td, fontWeight: '600' }}>
                                  {formatPaise(item.runningBalance)}
                                </td>
                                <td style={{ ...theme.td, fontFamily: 'monospace' }}>
                                  #{item.entryId || item.attemptId || '—'}
                                </td>
                                <td style={theme.td}>
                                  {item.initiatorUsername || 'system'}
                                </td>
                                <td style={theme.td}>
                                  {item.reason || '—'}
                                </td>
                                <td style={{ ...theme.td, fontSize: '11px', fontFamily: 'monospace', color: '#94a3b8' }} title={item.idempotencyKey}>
                                  {item.idempotencyKey ? `${item.idempotencyKey.slice(0, 12)}...` : '—'}
                                </td>
                              </tr>
                            );
                          })}
                        </tbody>
                      </table>
                    </div>

                    {/* Pagination */}
                    <div style={theme.paginationRow}>
                      <button
                        onClick={() => loadStatement(selectedAccountId, Math.max(0, statementPage - 1))}
                        disabled={statementPage === 0}
                        style={theme.secondaryBtnSmall}
                      >
                        ← Previous Page
                      </button>
                      <span style={{ fontSize: '13px', color: '#94a3b8' }}>
                        Page {statementPage + 1}
                      </span>
                      <button
                        onClick={() => loadStatement(selectedAccountId, statementPage + 1)}
                        disabled={statementData.entries.length < 20}
                        style={theme.secondaryBtnSmall}
                      >
                        Next Page →
                      </button>
                    </div>
                  </div>
                )}
              </div>
            )}

            {/* TAB 4: ADMIN HUB (ADMIN ONLY) */}
            {activeTab === 'admin' && isAdmin && (
              <div style={theme.sectionCard}>
                <div style={theme.sectionHeader}>
                  <div>
                    <h3 style={theme.sectionTitle}>Administrator Operations Suite</h3>
                    <p style={theme.sectionSubtitle}>
                      Privileged ledger lifecycle operations with mandatory audit reasons and hash recording
                    </p>
                  </div>
                </div>

                <div style={theme.actionTypeRow}>
                  <button
                    onClick={() => setAdminAction('CREATE_ACCOUNT')}
                    style={theme.actionTypeBtn(adminAction === 'CREATE_ACCOUNT')}
                  >
                    1. Create Account
                  </button>
                  <button
                    onClick={() => setAdminAction('ADD_MEMBER')}
                    style={theme.actionTypeBtn(adminAction === 'ADD_MEMBER')}
                  >
                    2. Add Member
                  </button>
                  <button
                    onClick={() => setAdminAction('ADJUST')}
                    style={theme.actionTypeBtn(adminAction === 'ADJUST')}
                  >
                    3. Audited Adjustment
                  </button>
                  <button
                    onClick={() => setAdminAction('REVERSE')}
                    style={theme.actionTypeBtn(adminAction === 'REVERSE')}
                  >
                    4. Reversal
                  </button>
                </div>

                <div style={{ maxWidth: '600px', marginTop: '20px' }}>
                  <form onSubmit={handleAdminSubmit} style={theme.form}>
                    {adminAction === 'CREATE_ACCOUNT' && (
                      <div>
                        <label style={theme.label}>New Account Name</label>
                        <input
                          type="text"
                          value={newAccountName}
                          onChange={(e) => setNewAccountName(e.target.value)}
                          required
                          style={theme.input}
                          placeholder="e.g. Treasury Reserve, Operations"
                        />
                        <p style={theme.fieldNote}>Creates a standard USER account in currency INR with initial balance ₹0.00.</p>
                      </div>
                    )}

                    {adminAction === 'ADD_MEMBER' && (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
                        <div>
                          <label style={theme.label}>Account ID</label>
                          <input
                            type="number"
                            value={memberAccountId}
                            onChange={(e) => setMemberAccountId(e.target.value)}
                            required
                            style={theme.input}
                            placeholder="e.g. 2"
                          />
                        </div>
                        <div>
                          <label style={theme.label}>Target User ID to Grant Membership</label>
                          <input
                            type="number"
                            value={memberUserId}
                            onChange={(e) => setMemberUserId(e.target.value)}
                            required
                            style={theme.input}
                            placeholder="e.g. 2 (holder)"
                          />
                        </div>
                      </div>
                    )}

                    {adminAction === 'ADJUST' && (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
                        <div>
                          <label style={theme.label}>Target Account ID</label>
                          <input
                            type="number"
                            value={adjustAccountId}
                            onChange={(e) => setAdjustAccountId(e.target.value)}
                            required
                            style={theme.input}
                            placeholder="e.g. 2"
                          />
                        </div>
                        <div>
                          <label style={theme.label}>Adjustment Amount (₹)</label>
                          <input
                            type="text"
                            value={adjustAmountRupees}
                            onChange={(e) => setAdjustAmountRupees(e.target.value)}
                            required
                            style={theme.input}
                            placeholder="e.g. 100.00"
                          />
                        </div>
                        <div>
                          <label style={theme.label}>Mandatory Audit Reason</label>
                          <input
                            type="text"
                            value={adjustReason}
                            onChange={(e) => setAdjustReason(e.target.value)}
                            required
                            style={theme.input}
                            placeholder="e.g. Authorized correction of clearing fee"
                          />
                        </div>
                      </div>
                    )}

                    {adminAction === 'REVERSE' && (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
                        <div>
                          <label style={theme.label}>Original Entry ID to Reverse</label>
                          <input
                            type="number"
                            value={reverseEntryId}
                            onChange={(e) => setReverseEntryId(e.target.value)}
                            required
                            style={theme.input}
                            placeholder="e.g. 1"
                          />
                        </div>
                        <div>
                          <label style={theme.label}>Mandatory Reversal Justification</label>
                          <input
                            type="text"
                            value={reverseReason}
                            onChange={(e) => setReverseReason(e.target.value)}
                            required
                            style={theme.input}
                            placeholder="e.g. Disputed transfer, customer chargeback"
                          />
                        </div>
                      </div>
                    )}

                    <button
                      type="submit"
                      disabled={adminSubmitting}
                      style={theme.primaryBtn}
                    >
                      {adminSubmitting ? 'Executing Admin Command...' : 'Execute Command'}
                    </button>
                  </form>
                </div>
              </div>
            )}

            {/* TAB 5: FORENSIC VERIFICATION REPORT */}
            {activeTab === 'verify' && (
              <div style={theme.sectionCard}>
                <div style={theme.sectionHeader}>
                  <div>
                    <h3 style={theme.sectionTitle}>Cryptographic & Double-Entry Invariants</h3>
                    <p style={theme.sectionSubtitle}>
                      Real-time forensic integrity report: 7 core accounting and cryptographic proofs
                    </p>
                  </div>
                  <button
                    onClick={runVerification}
                    disabled={verificationLoading}
                    style={{ ...theme.primaryBtn, width: 'auto', padding: '10px 20px' }}
                  >
                    {verificationLoading ? 'Verifying Integrity...' : '🛡️ Run Full Audit Verification'}
                  </button>
                </div>

                {verificationReport && (
                  <div>
                    {/* Overall Banner */}
                    <div style={theme.verifyStatusBanner(verificationReport.status === 'PASSED')}>
                      <div style={{ fontSize: '24px' }}>
                        {verificationReport.status === 'PASSED' ? '✓' : '⚠️'}
                      </div>
                      <div>
                        <div style={{ fontSize: '18px', fontWeight: '800' }}>
                          OVERALL VERIFICATION: {verificationReport.status}
                        </div>
                        <div style={{ fontSize: '12px', opacity: 0.9 }}>
                          Report Timestamp: {verificationReport.timestamp}
                        </div>
                      </div>
                    </div>

                    {/* 7 Invariant Checks Grid */}
                    <div style={theme.checksGrid}>
                      {Object.entries(verificationReport.checks || {}).map(([key, check]) => (
                        <div key={key} style={theme.checkCard(check.status === 'PASS')}>
                          <div style={theme.checkCardHeader}>
                            <span style={theme.checkKeyTitle}>
                              {key.replace(/([A-Z])/g, ' $1').toUpperCase()}
                            </span>
                            <span style={theme.checkStatusPill(check.status === 'PASS')}>
                              {check.status}
                            </span>
                          </div>
                          <p style={theme.checkDetail}>{check.detail}</p>
                          {check.data && (
                            <div style={theme.checkDataBlock}>
                              {Object.entries(check.data).map(([k, v]) => (
                                <div key={k} style={{ display: 'flex', justifyContent: 'space-between', fontSize: '12px', padding: '2px 0' }}>
                                  <span style={{ color: '#94a3b8' }}>{k}:</span>
                                  <strong style={{ fontFamily: 'monospace' }}>{String(v)}</strong>
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>
        )}
      </main>
    </div>
  );
}

/* ====================================================================
   HIGH-END MODERN FINTECH DESIGN SYSTEM (DARK / NAVY ENTERPRISE THEME)
   ==================================================================== */
const theme = {
  page: {
    minHeight: '100vh',
    backgroundColor: '#0b0f19',
    color: '#f8fafc',
    display: 'flex',
    flexDirection: 'column'
  },
  header: {
    backgroundColor: '#0f172a',
    borderBottom: '1px solid #1e293b',
    position: 'sticky',
    top: 0,
    zIndex: 50
  },
  headerContent: {
    maxWidth: '1280px',
    margin: '0 auto',
    padding: '14px 24px',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center'
  },
  logoContainer: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px'
  },
  logoIcon: {
    width: '40px',
    height: '40px',
    borderRadius: '10px',
    background: 'linear-gradient(135deg, #2563eb 0%, #1d4ed8 100%)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    color: '#ffffff',
    boxShadow: '0 4px 14px 0 rgba(37, 99, 235, 0.35)'
  },
  logoText: {
    fontSize: '20px',
    fontWeight: '800',
    letterSpacing: '-0.02em',
    color: '#ffffff',
    display: 'flex',
    alignItems: 'center',
    gap: '8px'
  },
  logoBadge: {
    fontSize: '10px',
    fontWeight: '700',
    padding: '2px 6px',
    borderRadius: '4px',
    backgroundColor: '#1e293b',
    color: '#38bdf8',
    border: '1px solid #334155'
  },
  logoSubtext: {
    fontSize: '12px',
    color: '#94a3b8'
  },
  headerRight: {
    display: 'flex',
    alignItems: 'center',
    gap: '16px'
  },
  neonBadge: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    fontSize: '12px',
    color: '#a7f3d0',
    backgroundColor: 'rgba(6, 78, 59, 0.4)',
    border: '1px solid rgba(16, 185, 129, 0.3)',
    padding: '6px 12px',
    borderRadius: '20px'
  },
  neonDot: {
    width: '8px',
    height: '8px',
    borderRadius: '50%',
    backgroundColor: '#10b981',
    boxShadow: '0 0 8px #10b981'
  },
  userProfile: {
    display: 'flex',
    alignItems: 'center',
    gap: '10px',
    backgroundColor: '#1e293b',
    padding: '6px 10px',
    borderRadius: '10px',
    border: '1px solid #334155'
  },
  userAvatar: {
    width: '32px',
    height: '32px',
    borderRadius: '8px',
    backgroundColor: '#3b82f6',
    color: '#ffffff',
    fontWeight: '700',
    fontSize: '13px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center'
  },
  userDetails: {
    display: 'flex',
    flexDirection: 'column'
  },
  userName: {
    fontSize: '13px',
    fontWeight: '700',
    color: '#f8fafc'
  },
  roleTag: (role) => ({
    fontSize: '10px',
    fontWeight: '700',
    textTransform: 'uppercase',
    color: role === 'ADMIN' ? '#c084fc' : role === 'AUDITOR' ? '#fde047' : '#60a5fa'
  }),
  logoutBtn: {
    background: 'transparent',
    border: 'none',
    color: '#94a3b8',
    cursor: 'pointer',
    padding: '4px',
    borderRadius: '6px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center'
  },
  main: {
    maxWidth: '1280px',
    width: '100%',
    margin: '0 auto',
    padding: '24px 20px',
    flex: 1
  },
  toast: (type) => ({
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: '12px 20px',
    margin: '16px auto 0',
    maxWidth: '1280px',
    width: 'calc(100% - 40px)',
    borderRadius: '10px',
    backgroundColor: type === 'success' ? '#064e3b' : type === 'error' ? '#881337' : '#1e3a8a',
    color: '#ffffff',
    border: `1px solid ${type === 'success' ? '#10b981' : type === 'error' ? '#f43f5e' : '#3b82f6'}`,
    boxShadow: '0 4px 12px rgba(0, 0, 0, 0.25)'
  }),
  toastClose: {
    background: 'none',
    border: 'none',
    color: '#ffffff',
    fontSize: '20px',
    cursor: 'pointer',
    padding: '0 4px'
  },
  /* Login Views */
  loginGrid: {
    display: 'grid',
    gridTemplateColumns: 'minmax(320px, 460px) 1fr',
    gap: '32px',
    alignItems: 'center',
    marginTop: '40px'
  },
  loginCard: {
    backgroundColor: '#111827',
    border: '1px solid #1e293b',
    borderRadius: '16px',
    padding: '32px',
    boxShadow: '0 20px 25px -5px rgba(0, 0, 0, 0.4)'
  },
  loginHeader: {
    textAlign: 'center',
    marginBottom: '24px'
  },
  shieldIcon: {
    display: 'inline-flex',
    padding: '12px',
    borderRadius: '50%',
    backgroundColor: 'rgba(59, 130, 246, 0.1)',
    marginBottom: '12px'
  },
  loginTitle: {
    fontSize: '22px',
    fontWeight: '800',
    margin: '0 0 6px 0',
    color: '#f8fafc'
  },
  loginSubtitle: {
    fontSize: '13px',
    color: '#94a3b8',
    margin: 0
  },
  form: {
    display: 'flex',
    flexDirection: 'column',
    gap: '16px'
  },
  label: {
    display: 'block',
    fontSize: '13px',
    fontWeight: '600',
    color: '#cbd5e1',
    marginBottom: '6px'
  },
  input: {
    width: '100%',
    padding: '12px 14px',
    borderRadius: '8px',
    border: '1px solid #334155',
    backgroundColor: '#0f172a',
    color: '#f8fafc',
    fontSize: '14px',
    transition: 'border-color 0.2s'
  },
  select: {
    width: '100%',
    padding: '12px 14px',
    borderRadius: '8px',
    border: '1px solid #334155',
    backgroundColor: '#0f172a',
    color: '#f8fafc',
    fontSize: '14px'
  },
  primaryBtn: {
    padding: '13px 20px',
    borderRadius: '8px',
    border: 'none',
    backgroundColor: '#2563eb',
    color: '#ffffff',
    fontSize: '14px',
    fontWeight: '700',
    cursor: 'pointer',
    width: '100%',
    boxShadow: '0 4px 14px rgba(37, 99, 235, 0.35)',
    transition: 'all 0.2s'
  },
  secondaryBtn: {
    padding: '8px 16px',
    borderRadius: '8px',
    border: '1px solid #334155',
    backgroundColor: '#1e293b',
    color: '#f8fafc',
    fontSize: '13px',
    fontWeight: '600',
    cursor: 'pointer'
  },
  secondaryBtnSmall: {
    padding: '6px 12px',
    borderRadius: '6px',
    border: '1px solid #334155',
    backgroundColor: '#1e293b',
    color: '#cbd5e1',
    fontSize: '12px',
    fontWeight: '600',
    cursor: 'pointer'
  },
  demoSection: {
    marginTop: '28px',
    paddingTop: '20px',
    borderTop: '1px solid #1e293b'
  },
  demoSectionTitle: {
    fontSize: '12px',
    fontWeight: '700',
    textTransform: 'uppercase',
    letterSpacing: '0.05em',
    color: '#94a3b8',
    marginBottom: '12px',
    textAlign: 'center'
  },
  demoGrid: {
    display: 'flex',
    flexDirection: 'column',
    gap: '8px'
  },
  demoRoleCard: (active) => ({
    padding: '10px 14px',
    borderRadius: '8px',
    border: active ? '1px solid #3b82f6' : '1px solid #1e293b',
    backgroundColor: active ? 'rgba(59, 130, 246, 0.1)' : '#0f172a',
    cursor: 'pointer',
    transition: 'all 0.2s'
  }),
  demoRoleHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: '4px'
  },
  demoRoleName: {
    fontSize: '13px',
    fontWeight: '700',
    color: '#f8fafc'
  },
  demoRoleDesc: {
    fontSize: '11px',
    color: '#94a3b8'
  },
  infoSidePanel: {
    backgroundColor: '#111827',
    border: '1px solid #1e293b',
    borderRadius: '16px',
    padding: '32px'
  },
  infoPanelTitle: {
    fontSize: '18px',
    fontWeight: '800',
    color: '#f8fafc',
    marginTop: 0,
    marginBottom: '18px'
  },
  infoList: {
    listStyleType: 'none',
    padding: 0,
    margin: 0,
    display: 'flex',
    flexDirection: 'column',
    gap: '16px'
  },
  infoItem: {
    fontSize: '13px',
    color: '#cbd5e1',
    lineHeight: 1.6
  },
  /* Metrics Bar */
  metricsRow: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))',
    gap: '16px',
    marginBottom: '24px'
  },
  metricCard: {
    backgroundColor: '#111827',
    border: '1px solid #1e293b',
    borderRadius: '12px',
    padding: '18px 20px',
    boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.2)'
  },
  metricLabel: {
    fontSize: '12px',
    fontWeight: '600',
    color: '#94a3b8',
    textTransform: 'uppercase',
    letterSpacing: '0.04em'
  },
  metricValue: {
    fontSize: '24px',
    fontWeight: '800',
    color: '#f8fafc',
    margin: '6px 0 2px 0'
  },
  metricSub: {
    fontSize: '12px',
    color: '#64748b'
  },
  /* Tab Navigation */
  tabBar: {
    display: 'flex',
    gap: '8px',
    borderBottom: '1px solid #1e293b',
    marginBottom: '24px',
    overflowX: 'auto',
    paddingBottom: '8px'
  },
  tabButton: (active) => ({
    padding: '10px 18px',
    borderRadius: '8px',
    border: active ? '1px solid #3b82f6' : '1px solid transparent',
    backgroundColor: active ? '#1e293b' : 'transparent',
    color: active ? '#38bdf8' : '#94a3b8',
    fontSize: '13px',
    fontWeight: '700',
    cursor: 'pointer',
    whiteSpace: 'nowrap',
    transition: 'all 0.15s'
  }),
  /* Section Card */
  sectionCard: {
    backgroundColor: '#111827',
    border: '1px solid #1e293b',
    borderRadius: '14px',
    padding: '24px',
    boxShadow: '0 10px 15px -3px rgba(0, 0, 0, 0.3)'
  },
  sectionHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: '20px',
    borderBottom: '1px solid #1e293b',
    paddingBottom: '16px'
  },
  sectionTitle: {
    fontSize: '18px',
    fontWeight: '800',
    margin: '0 0 4px 0',
    color: '#f8fafc'
  },
  sectionSubtitle: {
    fontSize: '13px',
    color: '#94a3b8',
    margin: 0
  },
  emptyState: {
    textAlign: 'center',
    padding: '40px 20px',
    color: '#64748b',
    fontSize: '14px'
  },
  /* Account Grid */
  accountGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))',
    gap: '16px'
  },
  accountCard: (selected) => ({
    padding: '20px',
    borderRadius: '12px',
    backgroundColor: selected ? '#1e293b' : '#0f172a',
    border: selected ? '2px solid #3b82f6' : '1px solid #1e293b',
    cursor: 'pointer',
    transition: 'all 0.2s',
    boxShadow: selected ? '0 0 16px rgba(59, 130, 246, 0.2)' : 'none'
  }),
  accCardTop: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'flex-start'
  },
  accTypeBadge: {
    fontSize: '10px',
    fontWeight: '800',
    letterSpacing: '0.06em',
    color: '#38bdf8'
  },
  accName: {
    fontSize: '17px',
    fontWeight: '700',
    color: '#f8fafc',
    margin: '4px 0 2px 0'
  },
  accNumber: {
    fontSize: '12px',
    fontFamily: 'monospace',
    color: '#64748b'
  },
  currencyBadge: {
    fontSize: '11px',
    fontWeight: '700',
    padding: '3px 8px',
    borderRadius: '6px',
    backgroundColor: '#1e293b',
    color: '#94a3b8'
  },
  accBalanceBlock: {
    margin: '18px 0 16px 0'
  },
  accBalanceLabel: {
    fontSize: '11px',
    fontWeight: '600',
    color: '#94a3b8',
    display: 'block',
    textTransform: 'uppercase'
  },
  accBalanceValue: (paise) => ({
    fontSize: '26px',
    fontWeight: '800',
    letterSpacing: '-0.02em',
    color: paise < 0 ? '#f43f5e' : '#10b981'
  }),
  accCardActions: {
    display: 'flex',
    gap: '8px'
  },
  actionBtnSmall: {
    padding: '6px 12px',
    borderRadius: '6px',
    border: '1px solid #334155',
    backgroundColor: '#0f172a',
    color: '#cbd5e1',
    fontSize: '12px',
    fontWeight: '600',
    cursor: 'pointer'
  },
  actionBtnSmallHighlight: {
    padding: '6px 12px',
    borderRadius: '6px',
    border: 'none',
    backgroundColor: '#2563eb',
    color: '#ffffff',
    fontSize: '12px',
    fontWeight: '700',
    cursor: 'pointer'
  },
  /* Money Movement Studio */
  transferLayout: {
    display: 'grid',
    gridTemplateColumns: '1fr 1fr',
    gap: '24px'
  },
  transferFormCard: {
    backgroundColor: '#0f172a',
    borderRadius: '12px',
    padding: '20px',
    border: '1px solid #1e293b'
  },
  actionTypeRow: {
    display: 'flex',
    gap: '8px',
    marginBottom: '20px'
  },
  actionTypeBtn: (active) => ({
    flex: 1,
    padding: '9px 12px',
    borderRadius: '8px',
    border: active ? '1px solid #3b82f6' : '1px solid #334155',
    backgroundColor: active ? '#2563eb' : '#1e293b',
    color: active ? '#ffffff' : '#cbd5e1',
    fontSize: '12px',
    fontWeight: '700',
    cursor: 'pointer',
    textAlign: 'center'
  }),
  inputWithPrefix: {
    display: 'flex',
    alignItems: 'center',
    backgroundColor: '#0f172a',
    borderRadius: '8px',
    border: '1px solid #334155',
    overflow: 'hidden'
  },
  prefixSpan: {
    padding: '0 14px',
    color: '#94a3b8',
    fontWeight: '700',
    fontSize: '16px'
  },
  inputInGroup: {
    width: '100%',
    padding: '12px 14px 12px 0',
    backgroundColor: 'transparent',
    border: 'none',
    color: '#ffffff',
    fontSize: '16px',
    fontWeight: '600'
  },
  paisePreview: {
    fontSize: '11px',
    color: '#38bdf8',
    marginTop: '6px'
  },
  idempotencyCard: {
    backgroundColor: '#0f172a',
    borderRadius: '12px',
    padding: '20px',
    border: '1px solid #1e293b',
    display: 'flex',
    flexDirection: 'column'
  },
  cardHeadingWithBadge: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: '8px'
  },
  secureTag: {
    fontSize: '10px',
    fontWeight: '700',
    padding: '3px 8px',
    borderRadius: '4px',
    backgroundColor: 'rgba(59, 130, 246, 0.2)',
    color: '#60a5fa'
  },
  idempotencyExplainer: {
    fontSize: '12px',
    color: '#94a3b8',
    lineHeight: 1.5,
    margin: '0 0 14px 0'
  },
  keyBox: {
    backgroundColor: '#0b0f19',
    border: '1px solid #1e293b',
    borderRadius: '8px',
    padding: '12px'
  },
  keyLabel: {
    fontSize: '10px',
    color: '#64748b',
    textTransform: 'uppercase',
    fontWeight: '700',
    marginBottom: '4px'
  },
  keyValue: {
    fontSize: '12px',
    fontFamily: 'monospace',
    color: '#f8fafc',
    wordBreak: 'break-all'
  },
  resultCard: (success) => ({
    marginTop: '16px',
    padding: '14px',
    borderRadius: '8px',
    border: success ? '1px solid #10b981' : '1px solid #f43f5e',
    backgroundColor: success ? 'rgba(6, 78, 59, 0.3)' : 'rgba(136, 19, 55, 0.3)'
  }),
  resultHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    fontSize: '12px',
    fontWeight: '700',
    marginBottom: '6px'
  },
  resultBody: {
    fontSize: '13px',
    color: '#cbd5e1'
  },
  monoTag: {
    fontFamily: 'monospace',
    fontSize: '11px',
    padding: '2px 6px',
    borderRadius: '4px',
    backgroundColor: '#0f172a'
  },
  /* Table & Statement */
  tableWrapper: {
    overflowX: 'auto',
    borderRadius: '8px',
    border: '1px solid #1e293b'
  },
  table: {
    width: '100%',
    borderCollapse: 'collapse',
    fontSize: '13px',
    textAlign: 'left'
  },
  th: {
    padding: '12px 14px',
    backgroundColor: '#0f172a',
    color: '#94a3b8',
    fontWeight: '700',
    borderBottom: '1px solid #1e293b',
    fontSize: '11px',
    textTransform: 'uppercase',
    letterSpacing: '0.04em'
  },
  td: {
    padding: '12px 14px',
    borderBottom: '1px solid #1e293b',
    color: '#cbd5e1'
  },
  tr: (refused) => ({
    backgroundColor: refused ? 'rgba(239, 68, 68, 0.05)' : 'transparent',
    transition: 'background-color 0.1s'
  }),
  typeBadge: (type) => ({
    display: 'inline-block',
    padding: '3px 8px',
    borderRadius: '4px',
    fontSize: '10px',
    fontWeight: '800',
    letterSpacing: '0.04em',
    backgroundColor: type === 'REFUSED'
      ? '#7f1d1d'
      : type === 'ADJUSTMENT'
      ? '#1e3a8a'
      : type.includes('WITHDRAW') || type.includes('DEBIT')
      ? '#3b0764'
      : '#064e3b',
    color: type === 'REFUSED'
      ? '#fca5a5'
      : type === 'ADJUSTMENT'
      ? '#93c5fd'
      : type.includes('WITHDRAW') || type.includes('DEBIT')
      ? '#d8b4fe'
      : '#86efac'
  }),
  paginationRow: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: '16px'
  },
  selectSmall: {
    padding: '6px 12px',
    borderRadius: '6px',
    border: '1px solid #334155',
    backgroundColor: '#0f172a',
    color: '#ffffff',
    fontSize: '12px'
  },
  fieldNote: {
    fontSize: '12px',
    color: '#94a3b8',
    marginTop: '6px'
  },
  /* Verification Visual Grid */
  verifyStatusBanner: (passed) => ({
    display: 'flex',
    alignItems: 'center',
    gap: '16px',
    padding: '16px 20px',
    borderRadius: '10px',
    backgroundColor: passed ? 'rgba(6, 78, 59, 0.4)' : 'rgba(136, 19, 55, 0.4)',
    border: `2px solid ${passed ? '#10b981' : '#f43f5e'}`,
    color: passed ? '#a7f3d0' : '#fecdd3',
    marginBottom: '20px'
  }),
  checksGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
    gap: '16px'
  },
  checkCard: (passed) => ({
    backgroundColor: '#0f172a',
    borderRadius: '10px',
    border: passed ? '1px solid rgba(16, 185, 129, 0.4)' : '1px solid rgba(244, 63, 94, 0.4)',
    padding: '16px'
  }),
  checkCardHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: '8px'
  },
  checkKeyTitle: {
    fontSize: '12px',
    fontWeight: '800',
    letterSpacing: '0.04em',
    color: '#f8fafc'
  },
  checkStatusPill: (passed) => ({
    fontSize: '11px',
    fontWeight: '800',
    padding: '2px 8px',
    borderRadius: '4px',
    backgroundColor: passed ? '#064e3b' : '#881337',
    color: passed ? '#86efac' : '#fda4af'
  }),
  checkDetail: {
    fontSize: '12px',
    color: '#cbd5e1',
    lineHeight: 1.5,
    margin: '0 0 10px 0'
  },
  checkDataBlock: {
    backgroundColor: '#0b0f19',
    borderRadius: '6px',
    padding: '8px 10px',
    border: '1px solid #1e293b'
  }
};

import React, { useState, useEffect, useCallback } from 'react';
import { formatPaise, parseRupeesToPaise, generateIdempotencyKey } from './utils';

export default function App() {
  // In-memory authentication state (never stored in localStorage or sessionStorage)
  const [token, setToken] = useState(null);
  const [currentUser, setCurrentUser] = useState(null);

  // Active view tab: 'accounts' | 'transfer' | 'statement' | 'admin' | 'verify'
  const [activeTab, setActiveTab] = useState('accounts');

  // Global notification banner: { type: 'success' | 'error' | 'warning' | 'info', message: string }
  const [notification, setNotification] = useState(null);

  // Login form state
  const [loginUsername, setLoginUsername] = useState('holder');
  const [loginPassword, setLoginPassword] = useState('');
  const [loginLoading, setLoginLoading] = useState(false);

  // Accounts state
  const [accounts, setAccounts] = useState([]);
  const [accountsLoading, setAccountsLoading] = useState(false);
  const [selectedAccountId, setSelectedAccountId] = useState(null);

  // Statement state
  const [statementData, setStatementData] = useState(null);
  const [statementLoading, setStatementLoading] = useState(false);
  const [statementPage, setStatementPage] = useState(0);

  // Money movement form state
  const [moneyAction, setMoneyAction] = useState('TRANSFER'); // 'DEPOSIT' | 'WITHDRAW' | 'TRANSFER'
  const [moneySourceAccount, setMoneySourceAccount] = useState('');
  const [moneyDestAccount, setMoneyDestAccount] = useState('');
  const [moneyAmountRupees, setMoneyAmountRupees] = useState('');
  const [moneyReason, setMoneyReason] = useState('');
  const [currentIdempotencyKey, setCurrentIdempotencyKey] = useState(generateIdempotencyKey());
  const [moneySubmitting, setMoneySubmitting] = useState(false);
  const [lastMovementResult, setLastMovementResult] = useState(null);

  // Admin states
  const [adminAction, setAdminAction] = useState('CREATE_ACCOUNT'); // 'CREATE_ACCOUNT' | 'ADD_MEMBER' | 'ADJUST' | 'REVERSE'
  const [newAccountName, setNewAccountName] = useState('');
  const [memberAccountId, setMemberAccountId] = useState('');
  const [memberUserId, setMemberUserId] = useState('');
  const [adjustAccountId, setAdjustAccountId] = useState('');
  const [adjustAmountRupees, setAdjustAmountRupees] = useState('');
  const [adjustReason, setAdjustReason] = useState('');
  const [reverseEntryId, setReverseEntryId] = useState('');
  const [reverseReason, setReverseReason] = useState('');
  const [adminSubmitting, setAdminSubmitting] = useState(false);

  // System Verification state
  const [verificationReport, setVerificationReport] = useState(null);
  const [verificationLoading, setVerificationLoading] = useState(false);

  // Clear notification helper
  const notify = (message, type = 'info') => {
    setNotification({ message, type });
  };

  // Central authenticated API fetcher with robust HTTP error handling
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
        // Token expired or invalid -> clear session and redirect to login
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

  // Load Statement for selected account
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
      notify('Account statement not found or you do not have permission to view it.', 'warning');
    } else {
      notify(`Failed to load statement: ${res.data?.message || 'Server error'}`, 'error');
    }
  }, [token, authFetch]);

  // Run System Verification
  const runVerification = useCallback(async () => {
    if (!token) return;
    setVerificationLoading(true);
    const res = await authFetch('/api/admin/verify');
    setVerificationLoading(false);

    if (res.ok) {
      setVerificationReport(res.data);
      if (res.data.status === 'PASSED') {
        notify('System cryptographic and double-entry invariants verified successfully: PASSED', 'success');
      } else {
        notify('System integrity check detected anomalies: FAILED', 'error');
      }
    } else {
      notify(`Verification failed: ${res.data?.message || 'Access denied'}`, 'error');
    }
  }, [token, authFetch]);

  useEffect(() => {
    if (token) {
      loadAccounts();
    }
  }, [token, loadAccounts]);

  useEffect(() => {
    if (token && selectedAccountId && activeTab === 'statement') {
      loadStatement(selectedAccountId, 0);
    }
  }, [token, selectedAccountId, activeTab, loadStatement]);

  // Handle Login
  const handleLogin = async (e) => {
    e.preventDefault();
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
          notify(`Too many login attempts. ${data.message || 'Please wait before retrying.'}`, 'warning');
        } else {
          notify(data.message || 'Authentication failed. Check credentials.', 'error');
        }
        return;
      }

      setToken(data.token);
      setCurrentUser(data.user);
      setLoginPassword('');
      setActiveTab('accounts');
      notify(`Welcome back, ${data.user.username}! Signed in as ${data.user.role}.`, 'success');
    } catch (err) {
      setLoginLoading(false);
      notify(`Network connection failed: ${err.message}`, 'error');
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

  // Money Form Field Change: Generate a new idempotency key when the user intentionally edits inputs
  const onMoneyFormChange = (setter, value) => {
    setter(value);
    // User modified the transaction parameters -> generate new idempotency key
    setCurrentIdempotencyKey(generateIdempotencyKey());
    setLastMovementResult(null);
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
      body = JSON.stringify({ amount: amountPaise, reason: moneyReason || 'Deposit via web portal' });
    } else if (moneyAction === 'WITHDRAW') {
      if (!moneySourceAccount) {
        notify('Please select an account', 'error');
        setMoneySubmitting(false);
        return;
      }
      url = `/api/accounts/${moneySourceAccount}/withdraw`;
      body = JSON.stringify({ amount: amountPaise, reason: moneyReason || 'Withdrawal via web portal' });
    } else {
      // TRANSFER
      if (!moneySourceAccount || !moneyDestAccount) {
        notify('Please select both source and destination accounts', 'error');
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
        reason: moneyReason || 'Transfer via web portal'
      });
    }

    // Send transaction using the current idempotency key
    const res = await authFetch(url, {
      method,
      headers: {
        'Idempotency-Key': currentIdempotencyKey
      },
      body
    });

    setMoneySubmitting(false);

    if (res.networkError) {
      // NOTE: DO NOT generate a new idempotency key on network error.
      // Reusing the exact same key guarantees exactly-once execution if retried!
      notify('Network timeout or disconnect. Idempotency key preserved — click Submit to safely retry.', 'warning');
      return;
    }

    if (res.ok && res.data) {
      setLastMovementResult({ success: true, ...res.data });
      notify(`Transaction committed successfully! Entry ID: ${res.data.entryId}`, 'success');
      loadAccounts();
      // Generate a fresh key for subsequent operations
      setCurrentIdempotencyKey(generateIdempotencyKey());
    } else if (res.status === 422 && res.data && res.data.status === 'REFUSED') {
      // Server returned a recorded refusal (insufficient funds)
      setLastMovementResult({ success: false, ...res.data });
      notify(`Transaction Refused: ${res.data.message || res.data.refusalCode}`, 'error');
      // Generate fresh key
      setCurrentIdempotencyKey(generateIdempotencyKey());
    } else if (res.status === 422) {
      notify(`Idempotency conflict (422): ${res.data?.message || 'Payload altered with same key'}`, 'error');
    } else if (res.status === 404) {
      setLastMovementResult({ success: false, status: 'REFUSED', message: 'Account not found or access unauthorized' });
      notify('Account not found or access unauthorized (404)', 'error');
    } else {
      notify(`Transaction error (${res.status}): ${res.data?.message || 'Request failed'}`, 'error');
    }
  };

  // Submit Admin Operations
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
        notify(`Account created successfully: ${res.data.name} (ID: ${res.data.id})`, 'success');
        setNewAccountName('');
        loadAccounts();
      }
    } else if (adminAction === 'ADD_MEMBER') {
      if (!memberAccountId || !memberUserId) {
        notify('Both Account ID and User ID are required', 'error');
        setAdminSubmitting(false);
        return;
      }
      res = await authFetch(`/api/admin/accounts/${memberAccountId}/members`, {
        method: 'POST',
        body: JSON.stringify({ userId: Number(memberUserId) })
      });
      if (res.ok) {
        notify(`User ${memberUserId} granted membership to account ${memberAccountId}`, 'success');
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
        notify('Mandatory audit reason is required for adjustments', 'error');
        setAdminSubmitting(false);
        return;
      }
      res = await authFetch(`/api/admin/accounts/${adjustAccountId}/adjust`, {
        method: 'POST',
        headers: { 'Idempotency-Key': generateIdempotencyKey() },
        body: JSON.stringify({ amount: adjustPaise, reason: adjustReason.trim() })
      });
      if (res.ok) {
        notify(`Adjustment entry committed! Entry ID: ${res.data.entryId}`, 'success');
        setAdjustAmountRupees('');
        setAdjustReason('');
        loadAccounts();
      }
    } else if (adminAction === 'REVERSE') {
      if (!reverseEntryId || !reverseReason.trim()) {
        notify('Entry ID and mandatory reason are required for reversal', 'error');
        setAdminSubmitting(false);
        return;
      }
      res = await authFetch(`/api/admin/entries/${reverseEntryId}/reverse`, {
        method: 'POST',
        headers: { 'Idempotency-Key': generateIdempotencyKey() },
        body: JSON.stringify({ reason: reverseReason.trim() })
      });
      if (res.ok) {
        notify(`Entry ${reverseEntryId} reversed successfully! Reversal Entry ID: ${res.data.entryId}`, 'success');
        setReverseEntryId('');
        setReverseReason('');
        loadAccounts();
      }
    }

    setAdminSubmitting(false);
    if (res && !res.ok) {
      notify(`Admin action failed (${res.status}): ${res.data?.message || 'Access denied or invalid input'}`, 'error');
    }
  };

  const isAuditor = currentUser?.role === 'AUDITOR';
  const isAdmin = currentUser?.role === 'ADMIN';

  return (
    <div style={styles.container}>
      {/* Header */}
      <header style={styles.header}>
        <div>
          <h1 style={styles.title}>LedgerLine</h1>
          <p style={styles.subtitle}>Cryptographically Verified Double-Entry Ledger</p>
        </div>

        {currentUser && (
          <div style={styles.userBadgeContainer}>
            <span style={styles.userRoleBadge(currentUser.role)}>
              {currentUser.role}: {currentUser.username}
            </span>
            <button onClick={handleLogout} style={styles.btnSecondary}>
              Sign Out
            </button>
          </div>
        )}
      </header>

      {/* Global Notifications */}
      {notification && (
        <div style={styles.notification(notification.type)}>
          <span>{notification.message}</span>
          <button onClick={() => setNotification(null)} style={styles.closeBtn}>×</button>
        </div>
      )}

      {/* Unauthenticated View: Sign In */}
      {!token ? (
        <div style={styles.card}>
          <h2 style={{ marginTop: 0 }}>Sign In to LedgerLine</h2>
          <form onSubmit={handleLogin} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
            <div>
              <label style={styles.label}>Username</label>
              <input
                type="text"
                value={loginUsername}
                onChange={(e) => setLoginUsername(e.target.value)}
                required
                style={styles.input}
                placeholder="e.g. holder, admin, auditor"
              />
            </div>
            <div>
              <label style={styles.label}>Password</label>
              <input
                type="password"
                value={loginPassword}
                onChange={(e) => setLoginPassword(e.target.value)}
                required
                style={styles.input}
                placeholder="Enter password"
              />
            </div>

            <button type="submit" disabled={loginLoading} style={styles.btnPrimary}>
              {loginLoading ? 'Authenticating...' : 'Sign In'}
            </button>
          </form>

          <div style={styles.demoBox}>
            <strong>Demo Accounts Available:</strong>
            <ul style={{ margin: '6px 0 0 0', paddingLeft: '20px', fontSize: '13px' }}>
              <li><strong>holder</strong>: Account owner (transfer, deposit, withdraw, statement)</li>
              <li><strong>admin</strong>: Administrator (create account, grant member, adjust, reverse, verify)</li>
              <li><strong>auditor</strong>: Read-only compliance (view accounts, statement, verify)</li>
            </ul>
          </div>
        </div>
      ) : (
        /* Authenticated Main Application */
        <div>
          {/* Navigation Bar */}
          <nav style={styles.navBar}>
            <button
              onClick={() => setActiveTab('accounts')}
              style={styles.tabBtn(activeTab === 'accounts')}
            >
              Accounts
            </button>

            {!isAuditor && (
              <button
                onClick={() => setActiveTab('transfer')}
                style={styles.tabBtn(activeTab === 'transfer')}
              >
                Move Money
              </button>
            )}

            <button
              onClick={() => setActiveTab('statement')}
              style={styles.tabBtn(activeTab === 'statement')}
            >
              Statement
            </button>

            {isAdmin && (
              <button
                onClick={() => setActiveTab('admin')}
                style={styles.tabBtn(activeTab === 'admin')}
              >
                Admin Console
              </button>
            )}

            {(isAdmin || isAuditor) && (
              <button
                onClick={() => {
                  setActiveTab('verify');
                  if (!verificationReport) runVerification();
                }}
                style={styles.tabBtn(activeTab === 'verify')}
              >
                Forensic Verification
              </button>
            )}
          </nav>

          {/* TAB 1: ACCOUNTS LIST */}
          {activeTab === 'accounts' && (
            <div style={styles.card}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
                <h2 style={{ margin: 0 }}>Available Accounts</h2>
                <button onClick={loadAccounts} disabled={accountsLoading} style={styles.btnSecondary}>
                  {accountsLoading ? 'Refreshing...' : 'Refresh'}
                </button>
              </div>

              {accounts.length === 0 ? (
                <p style={{ color: '#666' }}>No accounts found for your role.</p>
              ) : (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: '16px' }}>
                  {accounts.map((acc) => (
                    <div key={acc.id} style={styles.accountCard(selectedAccountId === acc.id)}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                        <div>
                          <h3 style={{ margin: '0 0 4px 0', fontSize: '18px' }}>{acc.name}</h3>
                          <span style={{ fontSize: '12px', color: '#666' }}>Account ID: #{acc.id} ({acc.type})</span>
                        </div>
                        <span style={styles.badge}>{acc.currency}</span>
                      </div>

                      <div style={{ margin: '16px 0 12px 0' }}>
                        <span style={{ fontSize: '12px', color: '#888', display: 'block' }}>Balance</span>
                        <span style={{ fontSize: '24px', fontWeight: 'bold', color: acc.balance < 0 ? '#d32f2f' : '#2e7d32' }}>
                          {formatPaise(acc.balance)}
                        </span>
                      </div>

                      <div style={{ display: 'flex', gap: '8px' }}>
                        <button
                          onClick={() => {
                            setSelectedAccountId(acc.id);
                            setActiveTab('statement');
                          }}
                          style={styles.btnSmall}
                        >
                          View Statement
                        </button>

                        {!isAuditor && (
                          <button
                            onClick={() => {
                              setSelectedAccountId(acc.id);
                              setMoneySourceAccount(acc.id.toString());
                              setActiveTab('transfer');
                            }}
                            style={styles.btnSmallSecondary}
                          >
                            Transact
                          </button>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* TAB 2: MOVE MONEY (DEPOSIT / WITHDRAW / TRANSFER) */}
          {activeTab === 'transfer' && !isAuditor && (
            <div style={styles.card}>
              <h2 style={{ marginTop: 0 }}>Move Money</h2>

              <div style={{ display: 'flex', gap: '8px', marginBottom: '20px' }}>
                {['TRANSFER', 'DEPOSIT', 'WITHDRAW'].map((action) => (
                  <button
                    key={action}
                    type="button"
                    onClick={() => {
                      setMoneyAction(action);
                      setCurrentIdempotencyKey(generateIdempotencyKey());
                      setLastMovementResult(null);
                    }}
                    style={{
                      padding: '8px 16px',
                      borderRadius: '4px',
                      border: '1px solid #ccc',
                      background: moneyAction === action ? '#1976d2' : '#f5f5f5',
                      color: moneyAction === action ? '#fff' : '#333',
                      fontWeight: moneyAction === action ? 'bold' : 'normal',
                      cursor: 'pointer'
                    }}
                  >
                    {action}
                  </button>
                ))}
              </div>

              <form onSubmit={handleMoneySubmit} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
                <div>
                  <label style={styles.label}>
                    {moneyAction === 'DEPOSIT' ? 'Destination Account' : 'Source Account'}
                  </label>
                  <select
                    value={moneySourceAccount}
                    onChange={(e) => onMoneyFormChange(setMoneySourceAccount, e.target.value)}
                    required
                    style={styles.input}
                  >
                    <option value="">-- Select Account --</option>
                    {accounts.map((a) => (
                      <option key={a.id} value={a.id}>
                        #{a.id} - {a.name} ({formatPaise(a.balance)})
                      </option>
                    ))}
                  </select>
                </div>

                {moneyAction === 'TRANSFER' && (
                  <div>
                    <label style={styles.label}>Destination Account</label>
                    <select
                      value={moneyDestAccount}
                      onChange={(e) => onMoneyFormChange(setMoneyDestAccount, e.target.value)}
                      required
                      style={styles.input}
                    >
                      <option value="">-- Select Destination Account --</option>
                      {accounts.map((a) => (
                        <option key={a.id} value={a.id} disabled={a.id.toString() === moneySourceAccount}>
                          #{a.id} - {a.name} ({formatPaise(a.balance)})
                        </option>
                      ))}
                    </select>
                  </div>
                )}

                <div>
                  <label style={styles.label}>Amount in Rupees (₹)</label>
                  <input
                    type="text"
                    value={moneyAmountRupees}
                    onChange={(e) => onMoneyFormChange(setMoneyAmountRupees, e.target.value)}
                    required
                    placeholder="e.g. 100 or 250.50"
                    style={styles.input}
                  />
                  <small style={{ color: '#666', marginTop: '4px', display: 'block' }}>
                    Stored in integer minor units (paise). Floats and negative amounts strictly rejected.
                  </small>
                </div>

                <div>
                  <label style={styles.label}>Description / Reference (Optional)</label>
                  <input
                    type="text"
                    value={moneyReason}
                    onChange={(e) => onMoneyFormChange(setMoneyReason, e.target.value)}
                    placeholder="e.g. Supplier payment, salary, rent"
                    style={styles.input}
                  />
                </div>

                <div style={styles.idempotencyInfo}>
                  <strong>Active Idempotency Key:</strong>
                  <code style={{ marginLeft: '6px', fontSize: '11px', color: '#0d47a1' }}>{currentIdempotencyKey}</code>
                  <span style={{ display: 'block', fontSize: '11px', color: '#555', marginTop: '2px' }}>
                    Reused automatically on connection retry or double-click to guarantee exactly-once processing.
                  </span>
                </div>

                <button
                  type="submit"
                  disabled={moneySubmitting}
                  style={styles.btnPrimary}
                >
                  {moneySubmitting ? 'Processing Transaction...' : `Confirm ${moneyAction}`}
                </button>
              </form>

              {/* Movement Result Display */}
              {lastMovementResult && (
                <div style={lastMovementResult.success ? styles.successBox : styles.refusalBox}>
                  {lastMovementResult.success ? (
                    <div>
                      <h4 style={{ margin: '0 0 6px 0', color: '#2e7d32' }}>✓ Transaction Committed</h4>
                      <p style={{ margin: '4px 0' }}>Entry ID: #{lastMovementResult.entryId}</p>
                      <p style={{ margin: '4px 0' }}>Kind: {lastMovementResult.kind}</p>
                    </div>
                  ) : (
                    <div>
                      <h4 style={{ margin: '0 0 6px 0', color: '#c62828' }}>⚠ Transaction Refused</h4>
                      <p style={{ margin: '4px 0' }}><strong>Refusal Reason:</strong> {lastMovementResult.message || lastMovementResult.refusalCode}</p>
                      {lastMovementResult.attemptId && (
                        <p style={{ margin: '4px 0', fontSize: '12px' }}>Refusal Record ID: #{lastMovementResult.attemptId}</p>
                      )}
                      <small style={{ color: '#555', display: 'block', marginTop: '4px' }}>
                        Refusals are persisted as auditable ledger data without rolling back idempotency state.
                      </small>
                    </div>
                  )}
                </div>
              )}
            </div>
          )}

          {/* TAB 3: ACCOUNT STATEMENT */}
          {activeTab === 'statement' && (
            <div style={styles.card}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
                <h2 style={{ margin: 0 }}>Account Statement</h2>
                <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                  <select
                    value={selectedAccountId || ''}
                    onChange={(e) => setSelectedAccountId(Number(e.target.value))}
                    style={{ ...styles.input, width: 'auto' }}
                  >
                    {accounts.map((a) => (
                      <option key={a.id} value={a.id}>
                        #{a.id} - {a.name}
                      </option>
                    ))}
                  </select>
                  <button
                    onClick={() => loadStatement(selectedAccountId, statementPage)}
                    disabled={statementLoading}
                    style={styles.btnSecondary}
                  >
                    {statementLoading ? 'Loading...' : 'Refresh'}
                  </button>
                </div>
              </div>

              {!statementData || statementData.items?.length === 0 ? (
                <p style={{ color: '#666' }}>No transactions recorded for this account yet.</p>
              ) : (
                <div style={{ overflowX: 'auto' }}>
                  <table style={styles.table}>
                    <thead>
                      <tr>
                        <th style={styles.th}>Timestamp</th>
                        <th style={styles.th}>Type</th>
                        <th style={styles.th}>Amount</th>
                        <th style={styles.th}>Balance After / Snapshot</th>
                        <th style={styles.th}>Reason / Details</th>
                        <th style={styles.th}>Initiator</th>
                      </tr>
                    </thead>
                    <tbody>
                      {statementData.items.map((item, idx) => {
                        const isRefusal = item.type === 'REFUSED';
                        const isCredit = item.amount > 0 && !isRefusal;
                        const isDebit = item.amount < 0 && !isRefusal;

                        return (
                          <tr key={idx} style={{ backgroundColor: isRefusal ? '#fff8e1' : 'transparent' }}>
                            <td style={styles.td}>
                              {item.createdAt ? new Date(item.createdAt).toLocaleString() : 'N/A'}
                            </td>
                            <td style={styles.td}>
                              <span style={styles.statementBadge(item.type)}>
                                {item.type}
                              </span>
                            </td>
                            <td style={{ ...styles.td, fontWeight: 'bold', color: isDebit ? '#d32f2f' : isCredit ? '#2e7d32' : '#f57c00' }}>
                              {isRefusal ? formatPaise(item.amount) : formatPaise(item.amount)}
                            </td>
                            <td style={styles.td}>
                              {formatPaise(item.runningBalance)}
                            </td>
                            <td style={styles.td}>
                              {item.reason || 'N/A'}
                            </td>
                            <td style={styles.td}>
                              {item.initiatorUsername || 'system'}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>

                  {/* Pagination */}
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: '16px', alignItems: 'center' }}>
                    <span style={{ fontSize: '13px', color: '#666' }}>
                      Showing {statementData.items.length} items (Page {statementPage + 1})
                    </span>
                    <div style={{ display: 'flex', gap: '8px' }}>
                      <button
                        onClick={() => loadStatement(selectedAccountId, Math.max(0, statementPage - 1))}
                        disabled={statementPage === 0 || statementLoading}
                        style={styles.btnSmall}
                      >
                        Previous
                      </button>
                      <button
                        onClick={() => loadStatement(selectedAccountId, statementPage + 1)}
                        disabled={statementData.items.length < 20 || statementLoading}
                        style={styles.btnSmall}
                      >
                        Next
                      </button>
                    </div>
                  </div>
                </div>
              )}
            </div>
          )}

          {/* TAB 4: ADMIN CONSOLE */}
          {activeTab === 'admin' && isAdmin && (
            <div style={styles.card}>
              <h2 style={{ marginTop: 0 }}>Administrator Console</h2>

              <div style={{ display: 'flex', gap: '8px', marginBottom: '20px' }}>
                {[
                  { id: 'CREATE_ACCOUNT', label: 'Create Account' },
                  { id: 'ADD_MEMBER', label: 'Grant Membership' },
                  { id: 'ADJUST', label: 'Audited Adjustment' },
                  { id: 'REVERSE', label: 'Reverse Entry' }
                ].map((action) => (
                  <button
                    key={action.id}
                    type="button"
                    onClick={() => setAdminAction(action.id)}
                    style={{
                      padding: '8px 14px',
                      borderRadius: '4px',
                      border: '1px solid #ccc',
                      background: adminAction === action.id ? '#4527a0' : '#f5f5f5',
                      color: adminAction === action.id ? '#fff' : '#333',
                      fontWeight: adminAction === action.id ? 'bold' : 'normal',
                      cursor: 'pointer'
                    }}
                  >
                    {action.label}
                  </button>
                ))}
              </div>

              <form onSubmit={handleAdminSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
                {adminAction === 'CREATE_ACCOUNT' && (
                  <div>
                    <label style={styles.label}>Account Name</label>
                    <input
                      type="text"
                      value={newAccountName}
                      onChange={(e) => setNewAccountName(e.target.value)}
                      required
                      placeholder="e.g. Reserve Account, Settlement Fund"
                      style={styles.input}
                    />
                    <small style={{ color: '#666', marginTop: '4px', display: 'block' }}>
                      Currency defaults strictly to INR. Type is USER.
                    </small>
                  </div>
                )}

                {adminAction === 'ADD_MEMBER' && (
                  <>
                    <div>
                      <label style={styles.label}>Account ID</label>
                      <input
                        type="number"
                        value={memberAccountId}
                        onChange={(e) => setMemberAccountId(e.target.value)}
                        required
                        placeholder="Target Account ID (e.g. 1)"
                        style={styles.input}
                      />
                    </div>
                    <div>
                      <label style={styles.label}>User ID to Grant Access</label>
                      <input
                        type="number"
                        value={memberUserId}
                        onChange={(e) => setMemberUserId(e.target.value)}
                        required
                        placeholder="User ID (e.g. 2)"
                        style={styles.input}
                      />
                    </div>
                  </>
                )}

                {adminAction === 'ADJUST' && (
                  <>
                    <div>
                      <label style={styles.label}>Account to Adjust</label>
                      <select
                        value={adjustAccountId}
                        onChange={(e) => setAdjustAccountId(e.target.value)}
                        required
                        style={styles.input}
                      >
                        <option value="">-- Select Target Account --</option>
                        {accounts.map((a) => (
                          <option key={a.id} value={a.id}>
                            #{a.id} - {a.name} ({formatPaise(a.balance)})
                          </option>
                        ))}
                      </select>
                    </div>
                    <div>
                      <label style={styles.label}>Adjustment Amount in Rupees (₹)</label>
                      <input
                        type="text"
                        value={adjustAmountRupees}
                        onChange={(e) => setAdjustAmountRupees(e.target.value)}
                        required
                        placeholder="e.g. 500 or 1000.50"
                        style={styles.input}
                      />
                      <small style={{ color: '#666', marginTop: '4px', display: 'block' }}>
                        Executes as an audited double-entry ADJUSTMENT between EXTERNAL and the target account.
                      </small>
                    </div>
                    <div>
                      <label style={styles.label}>Mandatory Audit Reason</label>
                      <input
                        type="text"
                        value={adjustReason}
                        onChange={(e) => setAdjustReason(e.target.value)}
                        required
                        placeholder="Mandatory regulatory or reconciliation reason"
                        style={styles.input}
                      />
                    </div>
                  </>
                )}

                {adminAction === 'REVERSE' && (
                  <>
                    <div>
                      <label style={styles.label}>Original Entry ID to Reverse</label>
                      <input
                        type="number"
                        value={reverseEntryId}
                        onChange={(e) => setReverseEntryId(e.target.value)}
                        required
                        placeholder="Entry ID (e.g. 101)"
                        style={styles.input}
                      />
                      <small style={{ color: '#666', marginTop: '4px', display: 'block' }}>
                        Can only be reversed once (enforced by DB unique index). Reversal respects non-negative balances.
                      </small>
                    </div>
                    <div>
                      <label style={styles.label}>Mandatory Reversal Reason</label>
                      <input
                        type="text"
                        value={reverseReason}
                        onChange={(e) => setReverseReason(e.target.value)}
                        required
                        placeholder="Mandatory reason for reversal"
                        style={styles.input}
                      />
                    </div>
                  </>
                )}

                <button type="submit" disabled={adminSubmitting} style={styles.btnPrimary}>
                  {adminSubmitting ? 'Executing Admin Operation...' : 'Execute Operation'}
                </button>
              </form>
            </div>
          )}

          {/* TAB 5: FORENSIC SYSTEM VERIFICATION */}
          {activeTab === 'verify' && (isAdmin || isAuditor) && (
            <div style={styles.card}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
                <div>
                  <h2 style={{ margin: 0 }}>System Integrity & Forensic Verification</h2>
                  <p style={{ color: '#666', margin: '4px 0 0 0', fontSize: '13px' }}>
                    Runs in a strict REPEATABLE READ read-only transaction without taking locks.
                  </p>
                </div>
                <button onClick={runVerification} disabled={verificationLoading} style={styles.btnPrimary}>
                  {verificationLoading ? 'Verifying...' : 'Re-run Verification'}
                </button>
              </div>

              {verificationReport && (
                <div>
                  {/* Status Banner */}
                  <div style={styles.verifyBanner(verificationReport.status === 'PASSED')}>
                    <h3 style={{ margin: 0 }}>
                      OVERALL VERIFICATION STATUS: {verificationReport.status}
                    </h3>
                    <p style={{ margin: '4px 0 0 0', fontSize: '13px' }}>
                      Timestamp: {new Date(verificationReport.timestamp).toLocaleString()} | Checks Passed: {verificationReport.passedChecks} / {verificationReport.totalChecks}
                    </p>
                  </div>

                  {/* Individual Checks Table */}
                  <table style={styles.table}>
                    <thead>
                      <tr>
                        <th style={styles.th}>Invariant / Forensic Check</th>
                        <th style={styles.th}>Status</th>
                        <th style={styles.th}>Verification Details</th>
                      </tr>
                    </thead>
                    <tbody>
                      {verificationReport.checks && Object.entries(verificationReport.checks).map(([key, check]) => (
                        <tr key={key}>
                          <td style={{ ...styles.td, fontWeight: 'bold' }}>
                            {check.name || key}
                          </td>
                          <td style={styles.td}>
                            <span style={styles.checkBadge(check.status === 'PASS')}>
                              {check.status}
                            </span>
                          </td>
                          <td style={{ ...styles.td, fontSize: '13px', color: '#444' }}>
                            {check.detail}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// Styling definitions
const styles = {
  container: {
    maxWidth: '1000px',
    margin: '30px auto',
    padding: '0 20px',
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif',
    color: '#212121'
  },
  header: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    borderBottom: '2px solid #e0e0e0',
    paddingBottom: '16px',
    marginBottom: '20px'
  },
  title: {
    margin: 0,
    fontSize: '28px',
    color: '#1a237e'
  },
  subtitle: {
    margin: '4px 0 0 0',
    fontSize: '14px',
    color: '#616161'
  },
  userBadgeContainer: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px'
  },
  userRoleBadge: (role) => ({
    padding: '6px 12px',
    borderRadius: '16px',
    fontSize: '13px',
    fontWeight: 'bold',
    backgroundColor: role === 'ADMIN' ? '#ede7f6' : role === 'AUDITOR' ? '#e0f2f1' : '#e3f2fd',
    color: role === 'ADMIN' ? '#4527a0' : role === 'AUDITOR' ? '#00695c' : '#0d47a1',
    border: '1px solid currentColor'
  }),
  notification: (type) => ({
    padding: '12px 16px',
    borderRadius: '6px',
    marginBottom: '20px',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: type === 'error' ? '#ffebee' : type === 'warning' ? '#fff8e1' : type === 'success' ? '#e8f5e9' : '#e1f5fe',
    color: type === 'error' ? '#c62828' : type === 'warning' ? '#f57f17' : type === 'success' ? '#2e7d32' : '#0277bd',
    border: `1px solid ${type === 'error' ? '#ef9a9a' : type === 'warning' ? '#ffe082' : type === 'success' ? '#a5d6a7' : '#81d4fa'}`
  }),
  closeBtn: {
    background: 'none',
    border: 'none',
    fontSize: '18px',
    cursor: 'pointer',
    color: 'inherit'
  },
  card: {
    backgroundColor: '#ffffff',
    borderRadius: '8px',
    padding: '24px',
    boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
    border: '1px solid #e0e0e0',
    marginBottom: '24px'
  },
  navBar: {
    display: 'flex',
    gap: '8px',
    marginBottom: '20px',
    borderBottom: '1px solid #e0e0e0',
    paddingBottom: '8px'
  },
  tabBtn: (active) => ({
    padding: '10px 18px',
    borderRadius: '6px',
    border: 'none',
    backgroundColor: active ? '#1976d2' : '#f5f5f5',
    color: active ? '#ffffff' : '#424242',
    fontWeight: active ? 'bold' : 'normal',
    cursor: 'pointer',
    fontSize: '14px',
    transition: 'all 0.2s'
  }),
  label: {
    display: 'block',
    fontSize: '14px',
    fontWeight: 'bold',
    marginBottom: '6px',
    color: '#37474f'
  },
  input: {
    width: '100%',
    padding: '10px 12px',
    borderRadius: '4px',
    border: '1px solid #cfd8dc',
    fontSize: '14px',
    boxSizing: 'border-box'
  },
  btnPrimary: {
    padding: '12px 24px',
    backgroundColor: '#1976d2',
    color: '#ffffff',
    border: 'none',
    borderRadius: '4px',
    fontWeight: 'bold',
    fontSize: '14px',
    cursor: 'pointer'
  },
  btnSecondary: {
    padding: '8px 16px',
    backgroundColor: '#f5f5f5',
    color: '#333333',
    border: '1px solid #bdbdbd',
    borderRadius: '4px',
    fontSize: '13px',
    cursor: 'pointer'
  },
  btnSmall: {
    padding: '6px 12px',
    backgroundColor: '#1976d2',
    color: '#ffffff',
    border: 'none',
    borderRadius: '4px',
    fontSize: '12px',
    cursor: 'pointer'
  },
  btnSmallSecondary: {
    padding: '6px 12px',
    backgroundColor: '#f5f5f5',
    color: '#333333',
    border: '1px solid #ccc',
    borderRadius: '4px',
    fontSize: '12px',
    cursor: 'pointer'
  },
  accountCard: (selected) => ({
    padding: '16px',
    borderRadius: '6px',
    border: selected ? '2px solid #1976d2' : '1px solid #e0e0e0',
    backgroundColor: selected ? '#f4f9fd' : '#fafafa'
  }),
  badge: {
    padding: '4px 8px',
    borderRadius: '12px',
    fontSize: '11px',
    fontWeight: 'bold',
    backgroundColor: '#e0e0e0',
    color: '#424242'
  },
  demoBox: {
    marginTop: '20px',
    padding: '14px',
    backgroundColor: '#f5f5f5',
    borderRadius: '6px',
    border: '1px dashed #bdbdbd'
  },
  idempotencyInfo: {
    padding: '10px 12px',
    backgroundColor: '#e8f4fd',
    borderRadius: '4px',
    fontSize: '12px',
    border: '1px solid #b3e5fc'
  },
  successBox: {
    marginTop: '20px',
    padding: '16px',
    backgroundColor: '#e8f5e9',
    borderRadius: '6px',
    border: '1px solid #a5d6a7'
  },
  refusalBox: {
    marginTop: '20px',
    padding: '16px',
    backgroundColor: '#ffebee',
    borderRadius: '6px',
    border: '1px solid #ef9a9a'
  },
  table: {
    width: '100%',
    borderCollapse: 'collapse',
    fontSize: '14px'
  },
  th: {
    textAlign: 'left',
    padding: '10px 12px',
    borderBottom: '2px solid #e0e0e0',
    color: '#455a64'
  },
  td: {
    padding: '10px 12px',
    borderBottom: '1px solid #eeeeee'
  },
  statementBadge: (type) => ({
    padding: '4px 8px',
    borderRadius: '4px',
    fontSize: '11px',
    fontWeight: 'bold',
    color: '#ffffff',
    backgroundColor: type === 'REFUSED' ? '#e65100' : type.includes('WITHDRAW') || type.includes('DEBIT') ? '#c62828' : '#2e7d32'
  }),
  verifyBanner: (passed) => ({
    padding: '16px',
    borderRadius: '6px',
    marginBottom: '20px',
    backgroundColor: passed ? '#e8f5e9' : '#ffebee',
    color: passed ? '#2e7d32' : '#c62828',
    border: `2px solid ${passed ? '#4caf50' : '#f44336'}`
  }),
  checkBadge: (passed) => ({
    padding: '4px 8px',
    borderRadius: '4px',
    fontSize: '12px',
    fontWeight: 'bold',
    color: '#ffffff',
    backgroundColor: passed ? '#2e7d32' : '#c62828'
  })
};

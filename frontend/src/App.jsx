import React, { useState } from 'react';

/**
 * SECURITY NOTE & TRADEOFF:
 * The JWT token is kept strictly in React memory (component state), NEVER in localStorage or sessionStorage.
 * - ADVANTAGE: Eliminates token exfiltration risks via malicious scripts reading localStorage (XSS-based credential theft).
 * - TRADEOFF: Refreshing the browser or opening a new tab will reset memory, requiring the user to re-authenticate.
 */
export default function App() {
  const [token, setToken] = useState(null); // In-memory JWT storage
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [profile, setProfile] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  const handleLogin = async (e) => {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      const response = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password })
      });

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.message || 'Login failed');
      }

      // Store token strictly in memory
      setToken(data.token);
      setProfile(data.user);
      setPassword('');
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  const handleFetchMe = async () => {
    if (!token) return;
    setError(null);

    try {
      const response = await fetch('/api/me', {
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.message || 'Failed to fetch profile');
      }

      setProfile(data);
    } catch (err) {
      setError(err.message);
    }
  };

  const handleLogout = () => {
    setToken(null);
    setProfile(null);
    setUsername('');
    setPassword('');
  };

  return (
    <div style={{ maxWidth: '600px', margin: '40px auto', fontFamily: 'system-ui, sans-serif', padding: '20px' }}>
      <header style={{ borderBottom: '2px solid #eee', paddingBottom: '16px', marginBottom: '24px' }}>
        <h1 style={{ margin: 0 }}>LedgerLine</h1>
        <p style={{ color: '#666', margin: '4px 0 0 0' }}>Double-Entry Money-Movement Platform</p>
      </header>

      {error && (
        <div style={{ background: '#ffebee', color: '#c62828', padding: '12px', borderRadius: '4px', marginBottom: '16px' }}>
          {error}
        </div>
      )}

      {!token ? (
        <form onSubmit={handleLogin} style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
          <h2>Sign In</h2>
          <div>
            <label style={{ display: 'block', marginBottom: '4px' }}>Username</label>
            <input
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
              style={{ width: '100%', padding: '8px', boxSizing: 'border-box' }}
            />
          </div>
          <div>
            <label style={{ display: 'block', marginBottom: '4px' }}>Password</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              style={{ width: '100%', padding: '8px', boxSizing: 'border-box' }}
            />
          </div>
          <button
            type="submit"
            disabled={loading}
            style={{ padding: '10px', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}
          >
            {loading ? 'Authenticating...' : 'Login'}
          </button>
        </form>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <h2>Authenticated Session</h2>
            <button
              onClick={handleLogout}
              style={{ padding: '6px 12px', background: '#d32f2f', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}
            >
              Sign Out
            </button>
          </div>

          <div style={{ background: '#f5f5f5', padding: '16px', borderRadius: '4px' }}>
            <h3>Current Principal (/api/me)</h3>
            <pre style={{ margin: 0, overflowX: 'auto' }}>
              {JSON.stringify(profile, null, 2)}
            </pre>
          </div>

          <button
            onClick={handleFetchMe}
            style={{ padding: '8px 16px', background: '#388e3c', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', alignSelf: 'flex-start' }}
          >
            Refresh Profile (GET /api/me)
          </button>

          <div style={{ marginTop: '20px', padding: '12px', background: '#e8f4fd', borderRadius: '4px', fontSize: '14px', color: '#0d47a1' }}>
            <strong>Security Architecture Note:</strong>
            <br />
            JWT is stored strictly in in-memory state. Closing or refreshing this tab clears the session immediately, preventing persistent storage tampering or XSS token harvesting.
          </div>
        </div>
      )}
    </div>
  );
}


# LedgerLine

LedgerLine is a secure, tamper-evident double-entry money-movement and ledger engine built with **Java 21**, **Spring Boot 3**, **PostgreSQL 16**, **Flyway**, and **React (Vite)**.

It guarantees strict zero-sum double-entry accounting, pessimistic ordered locking, immutable append-only ledger entries, cryptographically hash-chained audit trails, database-level trigger defenses, per-user idempotent transaction replay, token bucket rate limiting, and snapshot forensic verification.

---

## Architecture Diagram

```
                             HTTPS (TLS Terminated by Proxy / ALB)
                                              │
                                              ▼
                             ┌───────────────────────────────────┐
                             │    React Single-Page App (SPA)    │
                             │  (Token in memory, Zero floats)   │
                             └─────────────────┬─────────────────┘
                                               │
                                 Same-Origin REST API (/api)
                                               │
                                               ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          Spring Boot Application (Port 8080)                    │
│                                                                                 │
│   ┌─────────────────────┐   ┌──────────────────────┐   ┌────────────────────┐   │
│   │ Security / JWT Auth │   │ Bucket4j Rate Limiter│   │ Idempotency Service│   │
│   │  (Role RBAC & IDOR) │   │ (IP & User Buckets)  │   │ (Request Hashing)  │   │
│   └──────────┬──────────┘   └──────────┬───────────┘   └─────────┬──────────┘   │
│              │                         │                         │              │
│              └─────────────────────────┼─────────────────────────┘              │
│                                        ▼                                        │
│                           ┌─────────────────────────┐                           │
│                           │      LedgerService      │                           │
│                           │   (Pessimistic Locks)   │                           │
│                           └────────────┬────────────┘                           │
└────────────────────────────────────────┼────────────────────────────────────────┘
                                         │
                               JDBC (ledger_app role)
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                             PostgreSQL 16 Engine                                │
│                                                                                 │
│   ┌─────────────────────────────────────────────────────────────────────────┐   │
│   │ Accounts: CHECK (balance >= 0)  |  Row Lock: SELECT ... FOR UPDATE ASC  │   │
│   ├─────────────────────────────────────────────────────────────────────────┤   │
│   │ Postings: DEFERRABLE INITIALLY DEFERRED Constraint Trigger (SUM=0)      │   │
│   ├─────────────────────────────────────────────────────────────────────────┤   │
│   │ Ledger Entries & Audit Log: Append-Only Triggers (Block UPDATE/DEL/TRUNC)│  │
│   ├─────────────────────────────────────────────────────────────────────────┤   │
│   │ SHA-256 Cryptographic Hash Chains (Advisory Xact Lock Append)           │   │
│   └─────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Running Locally from a Clean Clone

### Prerequisites
- Docker & Docker Compose
- Or: Java 21 JDK, Node.js 20+, and PostgreSQL 16

### 1. One-Command Setup with Docker Compose
```bash
git clone https://github.com/Sandeep5476/Ledgerline.git
cd Ledgerline

# Start the full stack (Postgres 16 + Spring Boot + React UI)
docker compose up --build
```
The application will boot at `http://localhost:8080`.

### 2. Manual Local Development Setup
```bash
# 1. Start PostgreSQL (e.g. via Docker)
docker run --name ledger-pg -e POSTGRES_DB=ledgerline -e POSTGRES_PASSWORD=LedgerLocal_2026! -e POSTGRES_USER=ledger_owner -p 5432:5432 -d postgres:16-alpine

# 2. Build Frontend
cd frontend
npm install
npm run build
cd ..

# 3. Build & Run Backend
cd backend
mvn clean package -DskipTests
java -jar target/ledgerline-backend-0.0.1-SNAPSHOT.jar
```

---

## Seeded Credentials (Demo Accounts)

Three demo users are pre-seeded on application startup via environment variables:

| Role | Username | Password (from Env Var) | Permissions & Capabilities |
|---|---|---|---|
| **HOLDER** | `holder` | `${SEED_HOLDER_PASSWORD}` | Can view their own accounts, deposit, withdraw, transfer funds, view account statement. Strictly denied access to other users' accounts (404) and admin endpoints (403). |
| **ADMIN** | `admin` | `${SEED_ADMIN_PASSWORD}` | Can create accounts, grant memberships, perform audited balance adjustments, execute single reversals, and run forensic system verification. |
| **AUDITOR** | `auditor` | `${SEED_AUDITOR_PASSWORD}` | Strictly read-only compliance role. Can view all accounts, statements, entries, and run forensic system verification. **All write routes (POST/PUT/DELETE) return 403 Forbidden.** |

---

## Environment Variables

| Variable | Required | Description | Example / Default |
|---|:---:|---|---|
| `PORT` | Optional | Port for the HTTP server | `8080` |
| `DB_HOST` | Required | PostgreSQL database hostname | `localhost` or cloud DB host |
| `DB_PORT` | Optional | PostgreSQL database port | `5432` |
| `DB_NAME` | Required | Database name | `ledgerline` |
| `DB_USER` / `DB_MIGRATION_USER` | Required | Table owner role for Flyway migrations | `ledger_owner` |
| `DB_PASSWORD` / `DB_MIGRATION_PASSWORD` | Required | Password for the migration user | - |
| `APP_DB_USER` | Optional | Restricted application role | `ledger_app` (or same as owner in basic setups) |
| `APP_DB_PASSWORD` | Required | Application role password | - |
| `DB_SSLMODE` | Optional | PostgreSQL JDBC SSL mode | `require` (in prod), `prefer` (local) |
| `JWT_SECRET` | **Required** | HMAC-SHA256 key (minimum 32 bytes / 256 bits) | (Secret string) |
| `SEED_ADMIN_PASSWORD` | **Required** | Initial password for demo user `admin` | (Secret string) |
| `SEED_HOLDER_PASSWORD` | **Required** | Initial password for demo user `holder` | (Secret string) |
| `SEED_AUDITOR_PASSWORD` | **Required** | Initial password for demo user `auditor` | (Secret string) |
| `TRUSTED_PROXY_ENABLED` | Optional | When true, safely extracts client IP from reverse proxy | `true` (Render/ALB) / `false` |

---

## Deployment Steps (Render Blueprint)

1. Fork or clone this repository to GitHub.
2. Log into [Render.com](https://render.com) and navigate to **Blueprints**.
3. Click **New Blueprint Instance** and connect the repository.
4. Render will detect `render.yaml` and provision:
   - A managed PostgreSQL 16 database (`ledgerline-db`).
   - A Docker web service (`ledgerline`) with native health checks at `/actuator/health`.
   - Auto-generated cryptographically secure passwords for `JWT_SECRET` and seeded users.
5. Once deployment completes, test the live instance using the smoke test script:
   ```bash
   ./scripts/smoke.sh https://your-ledgerline-app.onrender.com
   ```

---

## Core Design Decisions

1. **Integer Minor Units (Paise) Everywhere**: All amounts are represented strictly as 64-bit `Long` paise. Floating point numbers (`Double`/`Float`) are disallowed across the entire stack: rejected by Jackson scalar coercion in the API, calculated using `Math.addExact`/`Math.subtractExact` in Java, stored as `BIGINT` in PostgreSQL, and formatted in React using string and BigInt operations.
2. **Pessimistic Ascending Row Locking (`SELECT ... FOR UPDATE`)**: Before checking funds or mutating balances, all involved accounts are locked in strictly ascending numerical ID order (`ORDER BY id ASC`). This guarantees that concurrent transactions never read stale balances, and opposing concurrent transfers (A->B and B->A) can never deadlock.
3. **Refusals Are Data**: Business validation failures (e.g. insufficient funds, unauthorized account access) are recorded into `ledger_attempts` and committed as normal HTTP responses. Refusals are not swallowed or lost on rollback.
4. **Per-User Idempotency with Row Locks**: Idempotency keys are scoped to `(user_id, key)`. Key acquisition uses `INSERT INTO idempotency_keys (...) ON CONFLICT (user_id, key) DO NOTHING` in the *same* database transaction as the movement. Concurrent duplicate requests block on the PostgreSQL row lock until the leader commits, then read and replay the identical response without duplicate executions.
5. **Cryptographic SHA-256 Hash Chains**: Both `ledger_entries` and `audit_log` maintain tamper-evident forward-hash chains (`hash = SHA-256(prev_hash || canonical_payload)`). Serialized using PostgreSQL transaction advisory locks (`pg_advisory_xact_lock`).
6. **Snapshot Invariant Verification (`GET /api/admin/verify`)**: Runs in an isolated `REPEATABLE READ` read-only transaction without taking write locks, proving double-entry balance, zero-sum postings, external flow equality, and chain integrity across the live system.

---

## What Was Deliberately Not Built

- **Overdraft Facilities / Credit Lines**: User accounts are strictly non-negative (`balance >= 0`). No negative user balances are permitted.
- **Multi-Currency Support**: All transactions and accounts are strictly denominated in `INR` (Indian Rupee / Paise). FX conversion and multi-currency exchange rates were omitted to keep the ledger zero-sum without currency fluctuation rounding loss.
- **Payment Holds / Two-Phase Escrow**: Transactions are executed atomically (one-step settlement) rather than multi-phase authorize-and-capture escrow holds.
- **Scheduled / Recurring Transfers**: No cron-based transfer execution inside the core ledger engine.
- **In-Product LLM / AI Automation**: LedgerLine executes strict, deterministic financial algorithms and formal mathematical double-entry invariants without non-deterministic AI agent calls in the financial critical path.

---

## Known Limitations / Unfinished

- **In-Memory Rate Limiting**: Token bucket rate limiters (Bucket4j) are stored in JVM memory (`ConcurrentHashMap`). In a horizontally auto-scaled multi-node cluster, limits apply per container instance. In a distributed multi-node production setup, Bucket4j should be backed by a shared Redis cluster.
- **Session Reset on Tab Refresh**: The JWT authentication token is kept strictly in React memory state for XSS immunity. Refreshing the browser clears the session and requires re-authenticating. For long-lived browser sessions, an HttpOnly refresh cookie pattern would be needed.
- **Hash Chain Append Throughput**: Serializing chain appends via `pg_advisory_xact_lock` guarantees zero chain forks across concurrent threads, but constrains total transactions per second on a single database instance to the serialized append throughput of PostgreSQL.

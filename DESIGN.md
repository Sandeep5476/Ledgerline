# LedgerLine Architecture & System Design Document

This document describes the core architectural, concurrency, cryptographic, and data integrity models implemented in LedgerLine.

---

## 1. Ledger Model (Double-Entry Bookkeeping)

- **Balanced Postings Invariant**: Every money-movement transaction is recorded as a single immutable `ledger_entries` journal row associated with two or more `postings` rows. Debits are recorded as negative integers (`amount < 0`), and credits are recorded as positive integers (`amount > 0`). The fundamental invariant enforced across the system is:
  $$\sum \text{postings.amount} = 0$$
- **Database-Level Zero-Sum Enforcement**: Enforced both in application logic and by a PostgreSQL `DEFERRABLE INITIALLY DEFERRED` constraint trigger (`trg_check_entry_zero_sum`). Even if an attacker executes raw SQL bypassing the application layer, the transaction will fail to commit if postings do not balance to zero.
- **Integer Minor Units (Paise)**: Amounts are strictly represented as 64-bit signed integers (`BIGINT` / `Long`). Floating-point arithmetic (`Double`/`Float`) is disallowed across all layers to eliminate floating-point rounding errors. Arithmetic operations use `Math.addExact` and `Math.subtractExact` to prevent silent integer overflow.
- **Running Balance (`balance_after`)**: Each posting persists the exact account `balance_after` at the time of insertion, providing a verifiable historical sequence of balances.

---

## 2. Isolation & Locking Model

- **Transaction Isolation Level**: The core money-movement method (`LedgerService.move`) runs under `@Transactional(isolation = Isolation.READ_COMMITTED)`.
- **Why READ COMMITTED with Pessimistic Locking?**:
  In a high-concurrency ledger, `SERIALIZABLE` isolation causes transaction abort storms (SQL state `40001` serialization failures), requiring complex exponential retry loops that degrade throughput. Instead, LedgerLine pairs `READ COMMITTED` with **pessimistic row-level write locks**:
  ```sql
  SELECT id, name, type, currency, balance, created_at
  FROM accounts
  WHERE id IN (?, ?)
  ORDER BY id ASC
  FOR UPDATE;
  ```
- **Guarantees of Ascending ID Ordering (`ORDER BY id ASC`)**:
  1. **Zero Deadlocks**: Concurrent transfers between the same two accounts in opposing directions (User A transferring to User B while User B transfers to User A) acquire locks in identical numerical order, completely eliminating cyclic deadlocks.
  2. **Never Read-Then-Lock**: Balances are read directly from the locked row within the transaction, eliminating race conditions and overdrafts.
  3. **Non-Negative Invariant**: User accounts enforce `CHECK (type <> 'USER' OR balance >= 0)` as defense-in-depth at the database engine level.

---

## 3. Exactly-Once Idempotency Model

- **Where Keys Live & Retention**: Idempotency keys live in the `idempotency_keys` table inside PostgreSQL. Because a financial ledger is permanent, idempotency keys are retained indefinitely (`status`, `request_hash`, `response_status`, `response_body`, `duplicate_count`).
- **User Scoping**: Keys are scoped per authenticated user (`UNIQUE(user_id, key)`). User A using key `"tx-101"` has zero effect or conflict with User B using `"tx-101"`.
- **In-Flight Concurrency & Atomic Acquisition**:
  ```sql
  INSERT INTO idempotency_keys (user_id, key, request_hash, status, created_at)
  VALUES (?, ?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP)
  ON CONFLICT (user_id, key) DO NOTHING;
  ```
  This insert occurs inside the **same database transaction** as the money move:
  - If the insert succeeds, this transaction owns the key, executes the transfer, updates status to `COMPLETED` with response body, and commits.
  - If it conflicts, PostgreSQL's row-level lock on the unique index tuple causes the second transaction to block until the first transaction finishes.
  - If the first transaction commits, the second transaction reads the completed response and returns it immediately (incrementing `duplicate_count`).
  - If the first transaction rolls back (e.g. system failure), its `IN_PROGRESS` insert rolls back with it, allowing subsequent retries to cleanly acquire the key.
- **Request Hash Verification**: A SHA-256 hash is computed over canonical `(method, path, userId, sortedPayload)`. Replaying with the same key and same payload returns the cached response. Replaying with the same key and altered payload returns `422 Unprocessable Entity` without executing the transaction.

---

## 4. Append-Only Enforcement & Audit Chains

- **Database Role Separation**:
  - `ledger_owner`: Owns tables and runs Flyway schema migrations.
  - `ledger_app`: Restricted application runtime role. Denied `UPDATE`, `DELETE`, and `TRUNCATE` on all ledger tables (`ledger_entries`, `postings`, `ledger_attempts`, `audit_log`).
- **Database Engine Triggers**:
  - `BEFORE UPDATE OR DELETE FOR EACH ROW` triggers raise exceptions on any mutation attempt, even by the table owner.
  - `BEFORE TRUNCATE FOR EACH STATEMENT` triggers block truncation attempts.
- **Cryptographic Hash Chaining**:
  Both `ledger_entries` and `audit_log` maintain forward SHA-256 hash chains:
  $$\text{hash}_n = \text{SHA-256}(\text{hash}_{n-1} \parallel \text{canonical\_payload}_n)$$
  Appends are serialized using PostgreSQL transaction advisory locks (`pg_advisory_xact_lock`), guaranteeing that concurrent transactions cannot fork the cryptographic chain.
- **Forensic Verification Endpoint (`GET /api/admin/verify`)**:
  Executes in a read-only `REPEATABLE READ` transaction snapshot without holding locks, verifying that:
  1. All postings sum to 0.
  2. Every account's cached balance matches the sum of its postings.
  3. Net external flow equals total user balances.
  4. No user balance is negative.
  5. Cryptographic hash chains are unbroken.
  6. Every individual entry's postings balance to zero.

---

## 5. What AI Was Used For

- Drafting Flyway SQL migrations for triggers, check constraints, and role separation.
- Scaffolded DTOs, controllers, and service layers adhering to Spring Boot best practices.
- Generating rigorous multi-threaded concurrency stress test suites using `ExecutorService` and `CountDownLatch`.
- Building the responsive React SPA with in-memory token state and integer-based currency formatting.
- Constructing the automated Docker multi-stage build, `render.yaml` Blueprint, and smoke testing script.

---

## 6. Where the AI Was Wrong

<!-- TODO: USER FEEDBACK & CORRECTIONS -->
*This section is reserved for the developer to document areas where AI recommendations or initial code required human correction, architectural redirection, or debugging:*

- [ ] **TODO: Concurrency & Lock Sequencing**: (Document any initial locking order discrepancies or transaction boundary nuances corrected during development)
- [ ] **TODO: Database Role Privilege Limits on Cloud Providers**: (Document adjustments made when cloud providers restrict `CREATEROLE` or table ownership)
- [ ] **TODO: React Currency Formatting**: (Document string and BigInt formatting corrections to avoid floating point math)
